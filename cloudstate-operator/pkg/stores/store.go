/*
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package stores

import (
	"context"

	cloudstate "github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/apis/v1alpha1"
	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"sigs.k8s.io/controller-runtime/pkg/builder"
)

type Store interface {
	InjectPodStoreConfig(ctx context.Context, name string, namespace string, pod *corev1.Pod, container *corev1.Container, store *cloudstate.StatefulStore) error

	ReconcileStatefulService(ctx context.Context, service *cloudstate.StatefulService, deployment *appsv1.Deployment, store *cloudstate.StatefulStore) ([]cloudstate.CloudstateCondition, error)
	SetupWithStatefulServiceController(builder *builder.Builder) error

	ReconcileStatefulStore(ctx context.Context, store *cloudstate.StatefulStore) (conditions []cloudstate.CloudstateCondition, updated bool, err error)
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
	true := true
	container.Env = append(container.Env, corev1.EnvVar{
		Name: name,
		ValueFrom: &corev1.EnvVarSource{
			SecretKeyRef: &corev1.SecretKeySelector{
				LocalObjectReference: secret,
				Key:                  key,
				Optional:             &true,
			},
		},
	})
}
