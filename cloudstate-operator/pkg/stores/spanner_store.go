package stores

import (
	"fmt"
	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/config"
	"golang.org/x/net/context"
	appsv1 "k8s.io/api/apps/v1"
	"sigs.k8s.io/controller-runtime/pkg/builder"
)
import corev1 "k8s.io/api/core/v1"
import cloudstate "github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/apis/v1alpha1"

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

func (s *SpannerStore) ReconcileStatefulStore(
	ctx context.Context,
	store *cloudstate.StatefulStore,
) ([]cloudstate.CloudstateCondition, bool, error) {
	return nil, false, nil
}

func (s *SpannerStore) SetupWithStatefulStoreController(builder *builder.Builder) error {
	return nil
}

func (s *SpannerStore) InjectPodStoreConfig(
	ctx context.Context,
	name string,
	namespace string,
	pod *corev1.Pod,
	cloudstateSidecarContainer *corev1.Container,
	store *cloudstate.StatefulStore,
) error {

	cloudstateSidecarContainer.Image = s.Config.Image

	spec := store.Spec.Spanner

	if spec == nil {
		return fmt.Errorf("nil Spanner store")
	}

	instance := spec.Instance
	if instance == "" {
		return fmt.Errorf("spanner instance must not be empty")
	}
	appendEnvVar(cloudstateSidecarContainer, SpannerInstanceEnvVar, instance)

	project := spec.Project
	if project == "" {
		return fmt.Errorf("spanner project must not be empty")
	}
	// TODO ensure if there's already a GCP_PROJECT environment variable, that it matches what we are setting
	appendEnvVar(cloudstateSidecarContainer, GcpProjectEnvVar, project)

	database := pod.Annotations[CloudstateSpannerDatabaseAnnotation]
	if database == "" {
		database = spec.Database
	}
	if database != "" {
		appendEnvVar(cloudstateSidecarContainer, SpannerDatabaseEnvVar, database)
	}

	suffix := pod.Annotations[CloudstateSpannerTableSuffixAnnotation]
	if suffix != "" {
		appendEnvVar(cloudstateSidecarContainer, SpannerTableSuffixEnvVar, suffix)
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
	cloudstateSidecarContainer.VolumeMounts = append(cloudstateSidecarContainer.VolumeMounts, corev1.VolumeMount{
		Name:      GoogleApplicationCredentialsVolumeName,
		MountPath: GoogleApplicationCredentialsPath,
	})
	appendEnvVar(cloudstateSidecarContainer, GoogleApplicationCredentialsEnvVar, GoogleApplicationCredentialsPath+"/key.json")

	return nil
}

func (s *SpannerStore) ReconcileStatefulService(
	ctx context.Context,
	statefulService *cloudstate.StatefulService,
	deployment *appsv1.Deployment,
	store *cloudstate.StatefulStore,
) ([]cloudstate.CloudstateCondition, error) {

	if statefulService.Spec.StoreConfig.Database != "" {
		deployment.Spec.Template.Annotations[CloudstateSpannerDatabaseAnnotation] = statefulService.Spec.StoreConfig.Database
	}
	if statefulService.Spec.StoreConfig.Secret != nil {
		deployment.Spec.Template.Annotations[CloudstateGcpSecretAnnotation] = statefulService.Spec.StoreConfig.Secret.Name
	}

	return nil, nil
}

func (s *SpannerStore) SetupWithStatefulServiceController(builder *builder.Builder) error {
	return nil
}
