package stores

import (
	"fmt"
	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/apis/v1alpha1"
	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/config"
	"github.com/go-logr/logr"
	"golang.org/x/net/context"
	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"sigs.k8s.io/controller-runtime/pkg/builder"
	"sigs.k8s.io/controller-runtime/pkg/client"
)

type StoreType string

const (
	CloudstateStatefulStoreAnnotation = "cloudstate.io/stateful-store"

	CassandraStoreType StoreType = "cassandra"
	NoStoreType        StoreType = "no-store"
	InMemoryStoreType  StoreType = "in-memory"
	SpannerStoreType   StoreType = "spanner"
	PostgresStoreType  StoreType = "postgres"
)

type Stores interface {
	// InjectPodStoreConfig injects configuration specific to a store.
	// At a minimum, the image that the container uses should be set. Additional environment variables specific to the
	// store may also be set, and volumes and mounts may be added if necessary. May also do some reconciliation of
	// other resources.
	InjectPodStoreConfig(ctx context.Context, name string, namespace string, pod *corev1.Pod,
		cloudstateSidecarContainer *corev1.Container) error

	ReconcileStatefulService(
		ctx context.Context,
		statefulService *v1alpha1.StatefulService,
		deployment *appsv1.Deployment,
	) ([]v1alpha1.CloudstateCondition, error)

	SetupWithStatefulServiceController(builder *builder.Builder) error

	ReconcileStatefulStore(
		ctx context.Context,
		store *v1alpha1.StatefulStore,
	) ([]v1alpha1.CloudstateCondition, bool, error)

	SetupWithStatefulStoreController(builder *builder.Builder) error
}

func NewMultiStores(client client.Client, stores map[StoreType]Store, cfg *config.OperatorConfig) *MultiStores {
	return &MultiStores{
		client:         client,
		stores:         stores,
		operatorConfig: cfg,
	}
}

func DefaultMultiStores(client client.Client, scheme *runtime.Scheme, log logr.Logger, cfg *config.OperatorConfig) *MultiStores {
	stores := make(map[StoreType]Store)
	if cfg.NoStore.Image != "" {
		stores[NoStoreType] = &NoStore{
			Config: &cfg.NoStore,
		}
	}
	if cfg.InMemory.Image != "" {
		stores[InMemoryStoreType] = &InMemoryStore{
			Config: &cfg.InMemory,
		}
	}
	if cfg.Cassandra.Image != "" {
		stores[CassandraStoreType] = &CassandraStore{
			Config: &cfg.Cassandra,
		}
	}
	if cfg.Spanner.Image != "" {
		stores[SpannerStoreType] = &SpannerStore{
			Config: &cfg.Spanner,
		}
	}
	if cfg.Postgres.Image != "" {
		stores[PostgresStoreType] = &PostgresStore{
			Client: client,
			Scheme: scheme,
			Log:    log.WithValues("store", "postgres"),
			Config: &cfg.Postgres,
			Gcp:    &cfg.Gcp,
		}
	}
	return NewMultiStores(client, stores, cfg)
}

type MultiStores struct {
	client         client.Client
	stores         map[StoreType]Store
	operatorConfig *config.OperatorConfig
}

var _ Stores = (*MultiStores)(nil)

func (m *MultiStores) ReconcileStatefulService(
	ctx context.Context,
	statefulService *v1alpha1.StatefulService,
	deployment *appsv1.Deployment,
) ([]v1alpha1.CloudstateCondition, error) {

	var storeType StoreType
	var storeName string
	store := &v1alpha1.StatefulStore{}

	if statefulService.Spec.StoreConfig == nil {
		storeType = NoStoreType
	} else {
		storeName = statefulService.Spec.StoreConfig.StatefulStore.Name
		if err := m.client.Get(ctx, client.ObjectKey{
			Namespace: statefulService.Namespace,
			Name:      storeName,
		}, store); err != nil {
			return nil, fmt.Errorf("error loading store %s from namespace %s: %w", storeName, statefulService.Namespace, err)
		}
		storeType = m.storeTypeFromSpec(store)
	}

	if storeType == "" {
		return []v1alpha1.CloudstateCondition{
			{
				Type:    v1alpha1.CloudstateNotReady,
				Status:  corev1.ConditionTrue,
				Reason:  "StoreNotDetermined",
				Message: fmt.Sprintf("Cannot determine type of store"),
			},
		}, nil
	}
	if m.stores[storeType] != nil {
		storeSupport := m.stores[storeType]
		conditions, err := storeSupport.ReconcileStatefulService(ctx, statefulService, deployment, store)
		if err != nil {
			return nil, fmt.Errorf("error injecting config for %s store named %s: %w", storeType, storeName, err)
		}
		return conditions, nil
	}

	return []v1alpha1.CloudstateCondition{
		{
			Type:    v1alpha1.CloudstateNotReady,
			Status:  corev1.ConditionTrue,
			Reason:  "StoreNotSupported",
			Message: fmt.Sprintf("Stateful store %s not supported", storeType),
		},
	}, nil
}

func (m *MultiStores) SetupWithStatefulServiceController(builder *builder.Builder) error {
	for _, store := range m.stores {
		if err := store.SetupWithStatefulServiceController(builder); err != nil {
			return err
		}
	}
	return nil
}

func (m *MultiStores) InjectPodStoreConfig(ctx context.Context, name string, namespace string, pod *corev1.Pod, cloudstateSidecarContainer *corev1.Container) error {
	var storeType StoreType
	store := &v1alpha1.StatefulStore{}

	storeName := pod.Annotations[CloudstateStatefulStoreAnnotation]
	if storeName == "" {
		storeType = NoStoreType
	} else {
		if err := m.client.Get(ctx, client.ObjectKey{
			Namespace: namespace,
			Name:      storeName,
		}, store); err != nil {
			return fmt.Errorf("error loading store %s from namespace %s: %w", storeName, namespace, err)
		}
		storeType = m.storeTypeFromSpec(store)
	}

	if storeType == "" {
		return fmt.Errorf("unable to determine type for store %s in namespace %s", storeName, namespace)
	}
	if m.stores[storeType] != nil {
		storeSupport := m.stores[storeType]
		if err := storeSupport.InjectPodStoreConfig(ctx, name, namespace, pod, cloudstateSidecarContainer, store); err != nil {
			return fmt.Errorf("error injecting config for %s store named %s: %w", storeType, storeName, err)
		}
	} else {
		return fmt.Errorf("don't know how to handle %s store", storeType)
	}

	return nil
}

func (m *MultiStores) ReconcileStatefulStore(
	ctx context.Context,
	store *v1alpha1.StatefulStore,
) ([]v1alpha1.CloudstateCondition, bool, error) {

	storeType := m.storeTypeFromSpec(store)

	if storeType == "" {
		return nil, false, fmt.Errorf("unable to determine type for store %s in namespace %s", store.Name, store.Namespace)
	}
	if m.stores[storeType] != nil {
		storeSupport := m.stores[storeType]
		return storeSupport.ReconcileStatefulStore(ctx, store)
	}
	return []v1alpha1.CloudstateCondition{
		{
			Type:    v1alpha1.CloudstateNotReady,
			Status:  corev1.ConditionTrue,
			Reason:  "StatefulStoreNotSupported",
			Message: fmt.Sprintf("StatefulStore of type %s not supported by this Cloudstate installation", storeType),
		},
	}, false, nil
}

func (m *MultiStores) SetupWithStatefulStoreController(builder *builder.Builder) error {
	for _, store := range m.stores {
		if err := store.SetupWithStatefulStoreController(builder); err != nil {
			return err
		}
	}
	return nil
}

func (m *MultiStores) storeTypeFromSpec(store *v1alpha1.StatefulStore) StoreType {
	if store.Spec.InMemory {
		return InMemoryStoreType
	} else if store.Spec.Cassandra != nil {
		return CassandraStoreType
	} else if store.Spec.Postgres != nil {
		return PostgresStoreType
	} else if store.Spec.Spanner != nil {
		return SpannerStoreType
	}
	return ""
}
