package stores

import (
	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/config"
	"golang.org/x/net/context"
	appsv1 "k8s.io/api/apps/v1"
	"sigs.k8s.io/controller-runtime/pkg/builder"
)
import corev1 "k8s.io/api/core/v1"
import cloudstate "github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/apis/v1alpha1"

type InMemoryStore struct {
	Config *config.InMemoryConfig
}

var _ Store = (*InMemoryStore)(nil)

func (i *InMemoryStore) ReconcileStatefulStore(
	ctx context.Context,
	store *cloudstate.StatefulStore,
) ([]cloudstate.CloudstateCondition, bool, error) {
	return nil, false, nil
}

func (i *InMemoryStore) SetupWithStatefulStoreController(builder *builder.Builder) error {
	return nil
}

func (i *InMemoryStore) InjectPodStoreConfig(
	ctx context.Context,
	name string,
	namespace string,
	pod *corev1.Pod,
	cloudstateSidecarContainer *corev1.Container,
	store *cloudstate.StatefulStore,
) error {
	cloudstateSidecarContainer.Image = i.Config.Image
	return nil
}

func (i *InMemoryStore) ReconcileStatefulService(
	ctx context.Context,
	statefulService *cloudstate.StatefulService,
	deployment *appsv1.Deployment,
	store *cloudstate.StatefulStore,
) ([]cloudstate.CloudstateCondition, error) {
	return nil, nil
}

func (i *InMemoryStore) SetupWithStatefulServiceController(builder *builder.Builder) error {
	return nil
}
