package stores

import (
	"golang.org/x/net/context"
	"sigs.k8s.io/controller-runtime/pkg/builder"
)
import appsv1 "k8s.io/api/apps/v1"
import corev1 "k8s.io/api/core/v1"
import cloudstate "github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/apis/v1alpha1"

type Store interface {
	InjectPodStoreConfig(
		ctx context.Context,
		name string,
		namespace string,
		pod *corev1.Pod,
		cloudstateSidecarContainer *corev1.Container,
		store *cloudstate.StatefulStore,
	) error

	ReconcileStatefulService(
		ctx context.Context,
		statefulService *cloudstate.StatefulService,
		deployment *appsv1.Deployment,
		store *cloudstate.StatefulStore,
	) ([]cloudstate.CloudstateCondition, error)

	SetupWithStatefulServiceController(builder *builder.Builder) error

	ReconcileStatefulStore(
		ctx context.Context,
		store *cloudstate.StatefulStore,
	) ([]cloudstate.CloudstateCondition, bool, error)

	SetupWithStatefulStoreController(builder *builder.Builder) error
}

func appendEnvVar(container *corev1.Container, name string, value string) {
	container.Env = append(container.Env, corev1.EnvVar{
		Name:  name,
		Value: value,
	})
}

func appendSecretEnvVar(container *corev1.Container, name string, secret corev1.LocalObjectReference, key string) {
	container.Env = append(container.Env, corev1.EnvVar{
		Name: name,
		ValueFrom: &corev1.EnvVarSource{
			SecretKeyRef: &corev1.SecretKeySelector{
				LocalObjectReference: secret,
				Key:                  key,
			},
		},
	})
}

func appendOptionalSecretEnvVar(container *corev1.Container, name string, secret corev1.LocalObjectReference, key string) {
	container.Env = append(container.Env, corev1.EnvVar{
		Name: name,
		ValueFrom: &corev1.EnvVarSource{
			SecretKeyRef: &corev1.SecretKeySelector{
				LocalObjectReference: secret,
				Key:                  key,
				Optional:             func() *bool { b := true; return &b }(),
			},
		},
	})
}
