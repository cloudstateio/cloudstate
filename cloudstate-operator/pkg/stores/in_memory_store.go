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
	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/config"
	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"sigs.k8s.io/controller-runtime/pkg/builder"
)

type InMemoryStore struct {
	Config *config.InMemoryConfig
}

var _ Store = (*InMemoryStore)(nil)

func (s *InMemoryStore) ReconcileStatefulStore(ctx context.Context, store *cloudstate.StatefulStore) ([]cloudstate.CloudstateCondition, bool, error) {
	return nil, false, nil
}

func (s *InMemoryStore) SetupWithStatefulStoreController(builder *builder.Builder) error {
	return nil
}

func (s *InMemoryStore) InjectPodStoreConfig(ctx context.Context, name string, namespace string, pod *corev1.Pod, cloudstateSidecarContainer *corev1.Container, store *cloudstate.StatefulStore) error {
	cloudstateSidecarContainer.Image = s.Config.Image
	if s.Config.Args != nil {
		cloudstateSidecarContainer.Args = s.Config.Args
	}
	return nil
}

func (s *InMemoryStore) ReconcileStatefulService(ctx context.Context, service *cloudstate.StatefulService, deployment *appsv1.Deployment, store *cloudstate.StatefulStore) ([]cloudstate.CloudstateCondition, error) {
	return nil, nil
}

func (s *InMemoryStore) SetupWithStatefulServiceController(builder *builder.Builder) error {
	return nil
}
