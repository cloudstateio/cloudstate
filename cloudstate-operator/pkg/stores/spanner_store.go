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
	"fmt"

	cloudstate "github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/apis/v1alpha1"
	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/config"
	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"sigs.k8s.io/controller-runtime/pkg/builder"
)

type SpannerStore struct {
	Config *config.SpannerConfig
}

var _ Store = (*SpannerStore)(nil)

const (
	CloudstateGcpSecretAnnotation          = "gcp.cloudstate.io/secret"
	CloudstateSpannerDatabaseAnnotation    = "spanner.cloudstate.io/database"
	CloudstateSpannerTableSuffixAnnotation = "spanner.cloudstate.io/table-suffix"

	SpannerInstanceEnvVar    = "SPANNER_INSTANCE"
	SpannerDatabaseEnvVar    = "SPANNER_DATABASE"
	SpannerTableSuffixEnvVar = "SPANNER_TABLE_SUFFIX"
	GcpProjectEnvVar         = "GCP_PROJECT"

	GoogleApplicationCredentialsEnvVar     = "GOOGLE_APPLICATION_CREDENTIALS"
	GoogleApplicationCredentialsVolumeName = "google-application-credentials"
	GoogleApplicationCredentialsPath       = "/var/run/secrets/cloudstate.io/google-application-credentials"
)

func (s *SpannerStore) ReconcileStatefulStore(ctx context.Context, store *cloudstate.StatefulStore) ([]cloudstate.CloudstateCondition, bool, error) {
	return nil, false, nil
}

func (s *SpannerStore) SetupWithStatefulStoreController(builder *builder.Builder) error {
	return nil
}

func (s *SpannerStore) InjectPodStoreConfig(ctx context.Context, name string, namespace string, pod *corev1.Pod,
	container *corev1.Container, store *cloudstate.StatefulStore) error {
	container.Image = s.Config.Image
	if s.Config.Args != nil {
		container.Args = s.Config.Args
	}
	spec := store.Spec.Spanner
	if spec == nil {
		return errors.New("nil Spanner store")
	}
	instance := spec.Instance
	if instance == "" {
		return errors.New("spanner instance must not be empty")
	}
	appendEnvVar(container, SpannerInstanceEnvVar, instance)

	project := spec.Project
	if project == "" {
		return fmt.Errorf("spanner project must not be empty")
	}
	// TODO ensure if there's already a GCP_PROJECT environment variable, that it matches what we are setting
	appendEnvVar(container, GcpProjectEnvVar, project)

	database := pod.Annotations[CloudstateSpannerDatabaseAnnotation]
	if database == "" {
		database = spec.Database
	}
	if database != "" {
		appendEnvVar(container, SpannerDatabaseEnvVar, database)
	}

	if suffix := pod.Annotations[CloudstateSpannerTableSuffixAnnotation]; suffix != "" {
		appendEnvVar(container, SpannerTableSuffixEnvVar, suffix)
	}

	secretName := pod.Annotations[CloudstateGcpSecretAnnotation]
	if secretName == "" {
		if spec.Secret == nil {
			return fmt.Errorf("a GCP secret must be specified to use Spanner")
		}
		secretName = spec.Secret.Name
	}

	// TODO check if there's already a gcp key secret mounted, if so, verify it matches the one we want
	pod.Spec.Volumes = append(pod.Spec.Volumes, corev1.Volume{
		Name: GoogleApplicationCredentialsVolumeName,
		VolumeSource: corev1.VolumeSource{
			Secret: &corev1.SecretVolumeSource{
				SecretName: secretName,
			},
		},
	})
	container.VolumeMounts = append(container.VolumeMounts, corev1.VolumeMount{
		Name:      GoogleApplicationCredentialsVolumeName,
		MountPath: GoogleApplicationCredentialsPath,
	})
	appendEnvVar(container, GoogleApplicationCredentialsEnvVar, GoogleApplicationCredentialsPath+"/key.json")

	return nil
}

func (s *SpannerStore) ReconcileStatefulService(ctx context.Context, service *cloudstate.StatefulService,
	deployment *appsv1.Deployment, store *cloudstate.StatefulStore,
) ([]cloudstate.CloudstateCondition, error) {
	if db := service.Spec.StoreConfig.Database; db != "" {
		deployment.Spec.Template.Annotations[CloudstateSpannerDatabaseAnnotation] = db
	}
	if secret := service.Spec.StoreConfig.Secret; s != nil {
		deployment.Spec.Template.Annotations[CloudstateGcpSecretAnnotation] = secret.Name
	}
	return nil, nil
}

func (s *SpannerStore) SetupWithStatefulServiceController(builder *builder.Builder) error {
	return nil
}
