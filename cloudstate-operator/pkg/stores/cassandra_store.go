package stores

import (
	"fmt"
	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/config"
	"golang.org/x/net/context"
	v1 "k8s.io/api/apps/v1"
	"sigs.k8s.io/controller-runtime/pkg/builder"
	"strconv"
)
import corev1 "k8s.io/api/core/v1"
import cloudstate "github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/apis/v1alpha1"

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

func (c *CassandraStore) ReconcileStatefulStore(
	ctx context.Context,
	store *cloudstate.StatefulStore,
) ([]cloudstate.CloudstateCondition, bool, error) {
	return nil, false, nil
}

func (c *CassandraStore) SetupWithStatefulStoreController(builder *builder.Builder) error {
	return nil
}

func (c *CassandraStore) ReconcileStatefulService(
	ctx context.Context,
	statefulService *cloudstate.StatefulService,
	deployment *v1.Deployment,
	store *cloudstate.StatefulStore,
) ([]cloudstate.CloudstateCondition, error) {

	if statefulService.Spec.StoreConfig.Database != "" {
		deployment.Spec.Template.Annotations[CloudstateCassandraKeyspaceAnnotation] = statefulService.Spec.StoreConfig.Database
	}
	if statefulService.Spec.StoreConfig.Secret != nil {
		deployment.Spec.Template.Annotations[CloudstateCassandraSecretAnnotation] = statefulService.Spec.StoreConfig.Secret.Name
	}

	return nil, nil
}

func (c *CassandraStore) SetupWithStatefulServiceController(builder *builder.Builder) error {
	return nil
}

func (c *CassandraStore) InjectPodStoreConfig(
	ctx context.Context,
	name string,
	namespace string,
	pod *corev1.Pod,
	cloudstateSidecarContainer *corev1.Container,
	store *cloudstate.StatefulStore,
) error {

	cloudstateSidecarContainer.Image = c.Config.Image

	spec := store.Spec.Cassandra

	if spec == nil {
		return fmt.Errorf("nil Cassandra store")
	}

	host := spec.Host
	if host == "" {
		return fmt.Errorf("cassandra host must not be empty")
	}
	appendEnvVar(cloudstateSidecarContainer, CassandraContactPointsEnvVar, host)

	if spec.Port != 0 {
		appendEnvVar(cloudstateSidecarContainer, CassandraPortEnvVar, strconv.Itoa(int(spec.Port)))
	}

	var secret *corev1.LocalObjectReference
	if pod.Annotations[CloudstateCassandraSecretAnnotation] != "" {
		secret = &corev1.LocalObjectReference{
			Name: pod.Annotations[CloudstateCassandraSecretAnnotation],
		}
	} else if spec.Credentials != nil {
		secret = spec.Credentials.Secret
	}

	keyspaceKey := "keyspace"
	usernameKey := "username"
	passwordKey := "password"
	if spec.Credentials != nil {
		if spec.Credentials.KeyspaceKey != "" {
			keyspaceKey = spec.Credentials.KeyspaceKey
		}
		if spec.Credentials.UsernameKey != "" {
			usernameKey = spec.Credentials.UsernameKey
		}
		if spec.Credentials.PasswordKey != "" {
			passwordKey = spec.Credentials.PasswordKey
		}
	}

	// Keyspace config
	if pod.Annotations[CloudstateCassandraKeyspaceAnnotation] != "" {
		appendEnvVar(cloudstateSidecarContainer, CassandraKeySpaceEnvVar, pod.Annotations[CloudstateCassandraKeyspaceAnnotation])
	} else if secret != nil {
		appendOptionalSecretEnvVar(cloudstateSidecarContainer, CassandraKeySpaceEnvVar, *secret, keyspaceKey)
	}

	// username/password config
	if secret != nil {
		appendSecretEnvVar(cloudstateSidecarContainer, CassandraUsernameEnvVar, *secret, usernameKey)
		appendSecretEnvVar(cloudstateSidecarContainer, CassandraPasswordEnvVar, *secret, passwordKey)
	}

	return nil
}
