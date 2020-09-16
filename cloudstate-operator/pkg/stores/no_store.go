package stores

import (
	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/config"
	"golang.org/x/net/context"
	"sigs.k8s.io/controller-runtime/pkg/builder"

	cloudstate "github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/apis/v1alpha1"
	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
)

type NoStore struct {
	Config *config.NoStoreConfig
}

var _ Store = (*NoStore)(nil)

func (n *NoStore) ReconcileStatefulStore(
	ctx context.Context,
	store *cloudstate.StatefulStore,
) ([]cloudstate.CloudstateCondition, bool, error) {
	return nil, false, nil
}

func (n *NoStore) SetupWithStatefulStoreController(builder *builder.Builder) error {
	return nil
}

func (n *NoStore) InjectPodStoreConfig(
	ctx context.Context,
	name string,
	namespace string,
	pod *corev1.Pod,
	cloudstateSidecarContainer *corev1.Container,
	store *cloudstate.StatefulStore,
) error {
	cloudstateSidecarContainer.Image = n.Config.Image

	return nil
}

func (n *NoStore) ReconcileStatefulService(
	ctx context.Context,
	statefulService *cloudstate.StatefulService,
	deployment *appsv1.Deployment,
	store *cloudstate.StatefulStore,
) ([]cloudstate.CloudstateCondition, error) {
	return nil, nil
}

func (n *NoStore) SetupWithStatefulServiceController(builder *builder.Builder) error {
	return nil
}
