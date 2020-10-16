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
	"errors"
	"strconv"

	cloudstate "github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/apis/v1alpha1"
	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/config"
	v1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"sigs.k8s.io/controller-runtime/pkg/builder"
)

type CassandraStore struct {
	Config *config.CassandraConfig
}

var _ Store = (*CassandraStore)(nil)

const (
	CloudstateCassandraKeyspaceAnnotation = "cassandra.cloudstate.io/keyspace"
	CloudstateCassandraSecretAnnotation   = "cassandra.cloudstate.io/secret"

	CassandraKeySpaceEnvVar      = "CASSANDRA_KEYSPACE"
	CassandraContactPointsEnvVar = "CASSANDRA_CONTACT_POINTS"
	CassandraPortEnvVar          = "CASSANDRA_PORT"
	CassandraUsernameEnvVar      = "CASSANDRA_USERNAME"
	CassandraPasswordEnvVar      = "CASSANDRA_PASSWORD"
)

func (s *CassandraStore) ReconcileStatefulStore(ctx context.Context, store *cloudstate.StatefulStore) ([]cloudstate.CloudstateCondition, bool, error) {
	return nil, false, nil
}

func (s *CassandraStore) SetupWithStatefulStoreController(builder *builder.Builder) error {
	return nil
}

func (s *CassandraStore) ReconcileStatefulService(ctx context.Context, service *cloudstate.StatefulService, deployment *v1.Deployment, store *cloudstate.StatefulStore) ([]cloudstate.CloudstateCondition, error) {
	if db := service.Spec.StoreConfig.Database; db != "" {
		deployment.Spec.Template.Annotations[CloudstateCassandraKeyspaceAnnotation] = db
	}
	if secret := service.Spec.StoreConfig.Secret; secret != nil {
		deployment.Spec.Template.Annotations[CloudstateCassandraSecretAnnotation] = secret.Name
	}
	return nil, nil
}

func (s *CassandraStore) SetupWithStatefulServiceController(builder *builder.Builder) error {
	return nil
}

func (s *CassandraStore) InjectPodStoreConfig(ctx context.Context, name string, namespace string, pod *corev1.Pod, container *corev1.Container, store *cloudstate.StatefulStore) error {
	container.Image = s.Config.Image
	if s.Config.Args != nil {
		container.Args = s.Config.Args
	}
	spec := store.Spec.Cassandra
	if spec == nil {
		return errors.New("nil Cassandra store")
	}
	host := spec.Host
	if host == "" {
		return errors.New("cassandra host must not be empty")
	}
	appendEnvVar(container, CassandraContactPointsEnvVar, host)
	if spec.Port != 0 {
		appendEnvVar(container, CassandraPortEnvVar, strconv.Itoa(int(spec.Port)))
	}

	var secret *corev1.LocalObjectReference
	if name := pod.Annotations[CloudstateCassandraSecretAnnotation]; name != "" {
		secret = &corev1.LocalObjectReference{
			Name: name,
		}
	} else if c := spec.Credentials; c != nil {
		secret = c.Secret
	}

	keyspaceKey := "keyspace"
	usernameKey := "username"
	passwordKey := "password"
	if c := spec.Credentials; c != nil {
		if c.KeyspaceKey != "" {
			keyspaceKey = c.KeyspaceKey
		}
		if c.UsernameKey != "" {
			usernameKey = c.UsernameKey
		}
		if c.PasswordKey != "" {
			passwordKey = c.PasswordKey
		}
	}

	// Keyspace config
	if ks := pod.Annotations[CloudstateCassandraKeyspaceAnnotation]; ks != "" {
		appendEnvVar(container, CassandraKeySpaceEnvVar, ks)
	} else if secret != nil {
		appendOptionalSecretEnvVar(container, CassandraKeySpaceEnvVar, *secret, keyspaceKey)
	}

	// username/password config
	if secret != nil {
		appendSecretEnvVar(container, CassandraUsernameEnvVar, *secret, usernameKey)
		appendSecretEnvVar(container, CassandraPasswordEnvVar, *secret, passwordKey)
	}

	return nil
}
