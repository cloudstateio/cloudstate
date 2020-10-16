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
	"encoding/base64"
	"errors"
	"fmt"
	"strconv"

	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/config"
	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/reconciliation"
	"github.com/go-logr/logr"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/builder"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/handler"
	"sigs.k8s.io/controller-runtime/pkg/source"

	gcloud "github.com/cloudstateio/cloudstate/cloudstate-operator/internal/google/api/sql.cnrm.cloud/v1beta1"
	cloudstate "github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/apis/v1alpha1"
	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

type PostgresStore struct {
	Client client.Client
	Scheme *runtime.Scheme
	Log    logr.Logger
	Config *config.PostgresConfig
	GCP    *config.GCPConfig
}

var _ Store = (*PostgresStore)(nil)

const (
	CloudstatePostgresDatabaseAnnotation = "postgres.cloudstate.io/database"
	CloudstatePostgresSecretAnnotation   = "postgres.cloudstate.io/secret"

	postgresServiceEnvVar  = "POSTGRES_SERVICE"
	postgresPortEnvVar     = "POSTGRES_PORT"
	postgresDatabaseEnvVar = "POSTGRES_DATABASE"
	postgresUsernameEnvVar = "POSTGRES_USERNAME"
	postgresPasswordEnvVar = "POSTGRES_PASSWORD"

	PostgresGoogleCloudSQLInstanceNotReady cloudstate.CloudstateConditionType = "CloudSqlInstanceNotReady"
	PostgresGoogleCloudSQLDatabaseNotReady cloudstate.CloudstateConditionType = "CloudSqlDatabaseNotReady"
	PostgresGoogleCloudSQLUserNotReady     cloudstate.CloudstateConditionType = "CloudSqlUserNotReady"

	postgresCnxSecretPrefix   = "postgres-cnx-"
	postgresCredsSecretPrefix = "postgres-creds-"
)

func (s *PostgresStore) SetupWithStatefulServiceController(builder *builder.Builder) error {
	if !s.Config.GoogleCloudSQL.Enabled {
		return nil
	}
	uSQLDatabase := gcloud.NewSQLDatabase()
	uSQLUser := gcloud.NewSQLUser()
	builder.
		Watches(&source.Kind{Type: uSQLDatabase}, &handler.EnqueueRequestForOwner{
			IsController: true,
			OwnerType:    &cloudstate.StatefulService{}}).
		Watches(&source.Kind{Type: uSQLUser}, &handler.EnqueueRequestForOwner{
			IsController: true,
			OwnerType:    &cloudstate.StatefulService{}})

	return nil
}

func (s *PostgresStore) SetupWithStatefulStoreController(builder *builder.Builder) error {
	if !s.Config.GoogleCloudSQL.Enabled {
		return nil
	}
	sqlInstance := gcloud.NewSQLInstance()
	builder.
		Watches(&source.Kind{Type: sqlInstance}, &handler.EnqueueRequestForOwner{
			IsController: true,
			OwnerType:    &cloudstate.StatefulStore{}}).
		Owns(&corev1.Secret{})
	return nil
}

func (s *PostgresStore) InjectPodStoreConfig(ctx context.Context, name string, namespace string, pod *corev1.Pod, container *corev1.Container, store *cloudstate.StatefulStore) error {
	container.Image = s.Config.Image
	if s.Config.Args != nil {
		container.Args = s.Config.Args
	}
	spec := store.Spec.Postgres
	if spec == nil {
		return errors.New("nil Postgres store")
	}

	host := spec.Host
	if host != "" {
		s.injectUnmanagedStoreConfig(container, host, spec, pod)
	} else if spec.GoogleCloudSQL != nil {
		if !s.Config.GoogleCloudSQL.Enabled {
			return errors.New("postgres Google Cloud SQL support is not enabled")
		}
		if err := s.injectGoogleCloudSQLStoreConfig(name, namespace, container, store, pod); err != nil {
			return err
		}
	} else {
		return errors.New("postgres host must not be empty")
	}

	return nil
}

func (s *PostgresStore) ReconcileStatefulService(ctx context.Context, service *cloudstate.StatefulService, deployment *appsv1.Deployment, store *cloudstate.StatefulStore) ([]cloudstate.CloudstateCondition, error) {
	log := s.Log.WithValues("statefulservice", service.Namespace+"/"+service.Name)
	spec := store.Spec.Postgres
	if spec == nil {
		return nil, errors.New("nil Postgres store")
	}
	host := spec.Host
	if host != "" {
		s.reconcileUnmanagedDeploymentStoreConfig(spec, service, deployment)
	} else if spec.GoogleCloudSQL != nil {
		if !s.Config.GoogleCloudSQL.Enabled {
			return []cloudstate.CloudstateCondition{
				{
					Type:    PostgresGoogleCloudSQLInstanceNotReady,
					Status:  corev1.ConditionTrue,
					Reason:  "GoogleCloudSQLSupportNotEnabled",
					Message: "Postgres Google Cloud SQL support is not enabled",
				},
			}, nil
		}
		return s.reconcileGoogleCloudSQLDeployment(ctx, service, deployment, store, log)
	} else {
		return nil, errors.New("postgres host must not be empty")
	}

	return nil, nil
}

func (s *PostgresStore) reconcileUnmanagedDeploymentStoreConfig(config *cloudstate.PostgresStore, service *cloudstate.StatefulService, deployment *appsv1.Deployment) {
	if db := service.Spec.StoreConfig.Database; db != "" {
		deployment.Spec.Template.Annotations[CloudstatePostgresDatabaseAnnotation] = db
	}
	if secret := service.Spec.StoreConfig.Secret; secret != nil {
		deployment.Spec.Template.Annotations[CloudstatePostgresSecretAnnotation] = secret.Name
	}
}

func (s *PostgresStore) injectUnmanagedStoreConfig(cloudstateSidecarContainer *corev1.Container, host string, spec *cloudstate.PostgresStore, pod *corev1.Pod) {
	appendEnvVar(cloudstateSidecarContainer, postgresServiceEnvVar, host)
	if spec.Port != 0 {
		appendEnvVar(cloudstateSidecarContainer, postgresPortEnvVar, strconv.Itoa(int(spec.Port)))
	}
	var secret *corev1.LocalObjectReference
	if name := pod.Annotations[CloudstatePostgresSecretAnnotation]; name != "" {
		secret = &corev1.LocalObjectReference{Name: name}
	} else if spec.Credentials != nil {
		secret = spec.Credentials.Secret
	}
	databaseKey := "database"
	usernameKey := "username"
	passwordKey := "password"
	if spec.Credentials != nil {
		if key := spec.Credentials.DatabaseKey; key != "" {
			databaseKey = key
		}
		if key := spec.Credentials.UsernameKey; key != "" {
			usernameKey = key
		}
		if key := spec.Credentials.PasswordKey; key != "" {
			passwordKey = key
		}
	}
	// Keyspace config
	if db := pod.Annotations[CloudstatePostgresDatabaseAnnotation]; db != "" {
		appendEnvVar(cloudstateSidecarContainer, postgresDatabaseEnvVar, db)
	} else if secret != nil {
		appendOptionalSecretEnvVar(cloudstateSidecarContainer, postgresDatabaseEnvVar, *secret, databaseKey)
	}
	// username/password config
	if secret != nil {
		appendSecretEnvVar(cloudstateSidecarContainer, postgresUsernameEnvVar, *secret, usernameKey)
		appendSecretEnvVar(cloudstateSidecarContainer, postgresPasswordEnvVar, *secret, passwordKey)
	}
}

func (s *PostgresStore) injectGoogleCloudSQLStoreConfig(name string, namespace string, cloudstateSidecarContainer *corev1.Container, store *cloudstate.StatefulStore, pod *corev1.Pod) error {
	databaseName := pod.Annotations[CloudstatePostgresDatabaseAnnotation]
	secretName := pod.Annotations[CloudstatePostgresSecretAnnotation]
	cnxSecretName := postgresCnxSecretPrefix + store.Name

	// We don't really want to create databases in a mutating webhook, so rather than trying to do that, we just
	// require an operator to already have done that.
	if secretName == "" || databaseName == "" {
		return errors.New("a Google Cloud SQL store requires using a StatefulService to reconcile it")
	}
	secret := corev1.LocalObjectReference{
		Name: secretName,
	}
	cnxSecret := corev1.LocalObjectReference{
		Name: cnxSecretName,
	}
	appendSecretEnvVar(cloudstateSidecarContainer, postgresServiceEnvVar, cnxSecret, "instanceIP")
	appendSecretEnvVar(cloudstateSidecarContainer, postgresPortEnvVar, cnxSecret, "port")
	appendEnvVar(cloudstateSidecarContainer, postgresDatabaseEnvVar, databaseName)
	appendSecretEnvVar(cloudstateSidecarContainer, postgresUsernameEnvVar, secret, "username")
	appendSecretEnvVar(cloudstateSidecarContainer, postgresPasswordEnvVar, secret, "password")

	return nil
}

func (s *PostgresStore) reconcileGoogleCloudSQLDeployment(ctx context.Context, statefulService *cloudstate.StatefulService, deployment *appsv1.Deployment, store *cloudstate.StatefulStore, log logr.Logger) ([]cloudstate.CloudstateCondition, error) {
	ownedSQLDatabases, err := reconciliation.GetControlledUnstructured(
		ctx, s.Client, statefulService.Name, statefulService.Namespace,
		statefulService.GroupVersionKind(), gcloud.SQLDatabaseGVK, true,
	)
	if err != nil {
		s.Log.Error(err, "unable to list owned SQL database(s)")
		return nil, err
	}
	var ownedSQLDatabase *gcloud.SQLDatabase
	if len(ownedSQLDatabases) > 0 {
		ownedSQLDatabase = &ownedSQLDatabases[0]
	}

	ownedSQLUsers, err := reconciliation.GetControlledUnstructured(
		ctx, s.Client, statefulService.Name, statefulService.Namespace,
		statefulService.GroupVersionKind(), gcloud.SQLUserGVK, true,
	)
	if err != nil {
		s.Log.Error(err, "unable to list owned SQL users")
		return nil, err
	}
	var ownedSQLUser *gcloud.SQLUser = nil
	if len(ownedSQLUsers) > 0 {
		ownedSQLUser = &ownedSQLUsers[0]
	}

	secretObj, err := reconciliation.GetControlledStructured(ctx, s.Client, statefulService, &corev1.SecretList{})
	if err != nil {
		return nil, err
	}
	var ownedSQLUserSecret *corev1.Secret
	if secretObj != nil {
		ownedSQLUserSecret = secretObj.(*corev1.Secret)
	}

	var conditions []cloudstate.CloudstateCondition
	databaseName := statefulService.Spec.StoreConfig.Database
	if databaseName == "" {
		databaseName = statefulService.Name
	}
	secretName := postgresCredsSecretPrefix + statefulService.Name

	var instanceName string
	if store.Status.Postgres != nil && store.Status.Postgres.GoogleCloudSQL != nil &&
		store.Status.Postgres.GoogleCloudSQL.InstanceName != "" {
		instanceName = store.Status.Postgres.GoogleCloudSQL.InstanceName
	} else {
		instanceName = s.createCloudSQLInstanceName(store)
		conditions = append(conditions, s.conditionForSQLInstance(nil))
	}
	// Create SQLDatabase, SQLUser, and secret (password).
	if err := s.reconcileGoogleCloudSQLDatabase(ctx, statefulService, ownedSQLDatabase, instanceName, databaseName, log); err != nil {
		return nil, fmt.Errorf("unable to reconcile sqldatabase: %w", err)
	}
	if err := s.reconcileGoogleCloudSQLUser(ctx, statefulService, ownedSQLUser, ownedSQLUserSecret, instanceName, secretName, log); err != nil {
		return nil, fmt.Errorf("unable to reconcile sqluser: %w", err)
	}

	// Add the relevant annotations to the deployment
	deployment.Annotations[CloudstatePostgresDatabaseAnnotation] = databaseName
	deployment.Annotations[CloudstatePostgresSecretAnnotation] = secretName

	// Add conditions for managed resources
	conditions = append(conditions, s.conditionForSQLDatabase(ownedSQLDatabase), s.conditionForSQLUser(ownedSQLUser))
	return conditions, nil
}

func (s *PostgresStore) createDesiredGoogleCloudSQLDatabase(statefulService *cloudstate.StatefulService, instanceName string, databaseName string) (*gcloud.SQLDatabase, error) {
	sqlDatabase := gcloud.MakeSQLDatabase(
		map[string]interface{}{
			"metadata": map[string]interface{}{
				"name":      databaseName,
				"namespace": statefulService.Namespace,
				"labels":    map[string]interface{}{},
			},
			"spec": map[string]interface{}{
				"charset":   "UTF8",
				"collation": "en_US.UTF8",
				"instanceRef": map[string]interface{}{
					"name": instanceName,
				},
			},
		},
	)
	sqlDatabase.SetLabels(reconciliation.SetCommonLabels(statefulService.Name, sqlDatabase.GetLabels()))
	if err := ctrl.SetControllerReference(statefulService, sqlDatabase, s.Scheme); err != nil {
		return nil, fmt.Errorf("unable to set owner: %w", err)
	}
	return sqlDatabase, nil
}

func (s *PostgresStore) createDesiredGoogleCloudSQLUser(statefulService *cloudstate.StatefulService, ownedSQLUserSecret *corev1.Secret, instanceName string) (*gcloud.SQLUser, error) {
	sqlUser := gcloud.MakeSQLUser(
		map[string]interface{}{
			"metadata": map[string]interface{}{
				"name":      statefulService.Name,
				"namespace": statefulService.Namespace,
				"labels":    map[string]interface{}{},
			},
			"spec": map[string]interface{}{
				"instanceRef": map[string]interface{}{
					"name": instanceName,
				},
				"password": map[string]interface{}{
					"valueFrom": map[string]interface{}{
						"secretKeyRef": map[string]string{
							"name": ownedSQLUserSecret.Name,
							"key":  "password",
						},
					},
				},
			},
		},
	)
	sqlUser.SetLabels(reconciliation.SetCommonLabels(statefulService.Name, sqlUser.GetLabels()))
	if err := ctrl.SetControllerReference(statefulService, sqlUser, s.Scheme); err != nil {
		return nil, fmt.Errorf("unable to set owner: %w", err)
	}
	return sqlUser, nil
}

func (s *PostgresStore) createDesiredGoogleCloudSQLUserSecret(statefulService *cloudstate.StatefulService, secretName string) (*corev1.Secret, error) {
	randomBytes, err := reconciliation.GenerateRandomBytes(16)
	if err != nil {
		return nil, err
	}
	password := base64.StdEncoding.EncodeToString(randomBytes)
	desiredSecret := &corev1.Secret{
		ObjectMeta: metav1.ObjectMeta{
			Name:      secretName,
			Namespace: statefulService.Namespace,
			Labels:    make(map[string]string),
		},
		Data: map[string][]byte{
			"username": []byte(statefulService.Name),
			"password": []byte(password),
		},
	}
	desiredSecret.SetLabels(reconciliation.SetCommonLabels(secretName, desiredSecret.GetLabels()))
	if err := ctrl.SetControllerReference(statefulService, desiredSecret, s.Scheme); err != nil {
		return nil, fmt.Errorf("unable to set owner: %w", err)
	}
	return desiredSecret, nil
}

func (s *PostgresStore) conditionForSQLDatabase(db *gcloud.SQLDatabase) cloudstate.CloudstateCondition {
	condition := cloudstate.CloudstateCondition{
		Type: PostgresGoogleCloudSQLDatabaseNotReady,
	}
	if db == nil {
		condition.Status = corev1.ConditionTrue // SQLDatabase uses Ready while StatefulService uses NotReady so status is opposite
		condition.Reason = "CloudSqlDatabaseNotCreated"
		condition.Message = "The Google Cloud SQL database as not been created"
		return condition
	}

	status, ok := db.UnstructuredContent()["status"]
	if !ok {
		condition.Status = corev1.ConditionTrue // SQLDatabase uses Ready while StatefulService uses NotReady so status is opposite
		condition.Reason = "CloudSqlDatabaseUnknownStatus"
		condition.Message = "The Google Cloud SQL database has unknown status"
		return condition
	}
	// There _has_ to be a better way to do this stuff…
	// TODO(review comment): probably runtime.DefaultUnstructuredConverter
	sqlDatabaseStatus := status.(map[string]interface{}) // ["conditions"][0]["type"]
	conditions := sqlDatabaseStatus["conditions"]        // .([]map[string]interface{})
	sqlDBCondition := conditions.([]interface{})[0]
	sqlDBCondType := sqlDBCondition.(map[string]interface{})["type"].(string)
	sqlDBCondStatus := sqlDBCondition.(map[string]interface{})["status"].(string) // corev1.ConditionStatus
	sqlDBCondReason := sqlDBCondition.(map[string]interface{})["reason"].(string)
	sqlDBCondMessage := sqlDBCondition.(map[string]interface{})["message"].(string)
	if !(sqlDBCondType == "Ready" && sqlDBCondStatus == "True") {
		condition.Status = corev1.ConditionTrue // SQLDatabase uses Ready while StatefulService uses NotReady so status is opposite
		condition.Reason = "CloudSqlDatabaseUnavailable"
		condition.Message = fmt.Sprintf("The Google Cloud SQL database is not yet ready: %s: %s", sqlDBCondReason, sqlDBCondMessage)
		return condition
	}

	condition.Status = corev1.ConditionFalse // SQLDatabase uses Ready while StatefulService uses NotReady so status is opposite
	condition.Reason = "CloudSqlDatabaseReady"
	condition.Message = "The Google Cloud SQL database has been provisioned"
	return condition
}

func (s *PostgresStore) conditionForSQLUser(user *gcloud.SQLUser) cloudstate.CloudstateCondition {
	condition := cloudstate.CloudstateCondition{
		Type: PostgresGoogleCloudSQLUserNotReady,
	}
	if user == nil {
		condition.Status = corev1.ConditionTrue // SQLUser uses Ready while StatefulService uses NotReady so status is opposite
		condition.Reason = "CloudSqlUserNotCreated"
		condition.Message = "The Google Cloud SQL user as not been created"
		return condition
	}
	status, ok := user.UnstructuredContent()["status"]
	if !ok {
		condition.Status = corev1.ConditionTrue // SQLUser uses Ready while StatefulService uses NotReady so status is opposite
		condition.Reason = "CloudSqlUserUnknownStatus"
		condition.Message = "The Google Cloud SQL user has unknown status"
		return condition
	}
	// There _has_ to be a better way to do this stuff...
	sqlUserStatus := status.(map[string]interface{}) // ["conditions"][0]["type"]
	conditions := sqlUserStatus["conditions"]        // .([]map[string]interface{})
	sqlUserCondition := conditions.([]interface{})[0]
	sqlUserCondType := sqlUserCondition.(map[string]interface{})["type"].(string)
	sqlUserCondStatus := sqlUserCondition.(map[string]interface{})["status"].(string) // corev1.ConditionStatus
	sqlUserCondReason := sqlUserCondition.(map[string]interface{})["reason"].(string)
	sqlUserCondMessage := sqlUserCondition.(map[string]interface{})["message"].(string)

	if !(sqlUserCondType == "Ready" && sqlUserCondStatus == "True") {
		condition.Status = corev1.ConditionTrue // SQLUser uses Ready while StatefulService uses NotReady so status is opposite
		condition.Reason = "CloudSqlUserUnavailable"
		condition.Message = fmt.Sprintf("The Google Cloud SQL user is not yet ready: %s: %s", sqlUserCondReason, sqlUserCondMessage)
		return condition
	}

	condition.Status = corev1.ConditionFalse // SQLUser uses Ready while StatefulService uses NotReady so status is opposite
	condition.Reason = "CloudSqlUserReady"
	condition.Message = "The Google Cloud SQL user has been provisioned"
	return condition
}

func (s *PostgresStore) conditionForSQLInstance(instance *gcloud.SQLInstance) cloudstate.CloudstateCondition {
	c := cloudstate.CloudstateCondition{
		Type: PostgresGoogleCloudSQLInstanceNotReady,
	}
	if instance == nil {
		c.Status = corev1.ConditionTrue
		c.Reason = "CloudSqlInstanceNotCreated"
		c.Message = "The Google Cloud SQL instance has not yet been created"
		return c
	}
	// is SQLInstance ready?
	// There _has_ to be a better way to do this...
	sqlIContent := instance.UnstructuredContent()
	sqlIStatusIface, ok := sqlIContent["status"]
	if !ok {
		c.Status = corev1.ConditionTrue
		c.Reason = "CloudSqlInstanceUnknownStatus"
		c.Message = "The Google Cloud SQL instance has unknown status"
		return c
	}
	sqlIStatus := sqlIStatusIface.(map[string]interface{})
	conditions := sqlIStatus["conditions"] // .([]map[string]interface{})
	sqlICondition := conditions.([]interface{})[0]
	sqlICondType := sqlICondition.(map[string]interface{})["type"].(string)
	sqlICondStatus := sqlICondition.(map[string]interface{})["status"].(string) // corev1.ConditionStatus
	sqlICondReason := sqlICondition.(map[string]interface{})["reason"].(string)
	sqlICondMessage := sqlICondition.(map[string]interface{})["message"].(string)

	if sqlICondType != "Ready" { // some type we don't know about
		c.Status = corev1.ConditionTrue
		c.Reason = "CloudSqlInstanceNotReady"
		c.Message = fmt.Sprintf(
			"The Google Cloud SQL has status: %s:%s, %s: %s",
			sqlICondType, sqlICondStatus, sqlICondReason, sqlICondMessage,
		)
		return c
	}
	if sqlICondStatus != "True" {
		c.Status = corev1.ConditionTrue
		c.Reason = "CloudSqlInstanceNotReady"
		c.Message = fmt.Sprintf("The Google Cloud SQL instance has status: %s -- %s", sqlICondReason, sqlICondMessage)
		return c
	}
	c.Status = corev1.ConditionFalse
	c.Reason = "CloudSqlInstanceReady"
	c.Message = "The Google Cloud SQL instance is ready"
	return c
}

// TODO(review comment): these long arguments lists have to be captured with a type somehow, I think.
func (s *PostgresStore) reconcileGoogleCloudSQLUser(ctx context.Context, statefulService *cloudstate.StatefulService, actualSQLUser *gcloud.SQLUser, actualSecret *corev1.Secret, instanceName string, secretName string, log logr.Logger) error {
	if actualSecret == nil {
		desiredSecret, err := s.createDesiredGoogleCloudSQLUserSecret(statefulService, secretName)
		if err != nil {
			return err
		}
		log.Info("Creating secret for CloudSQL user", "secret", secretName)
		if err := s.Client.Create(ctx, desiredSecret); err != nil {
			return err
		}
		actualSecret = desiredSecret
	}

	desired, err := s.createDesiredGoogleCloudSQLUser(statefulService, actualSecret, instanceName)
	if err != nil {
		return err
	}
	if actualSQLUser == nil {
		reconciliation.SetLastApplied(desired)
		log.Info("Creating CloudSQL user", "SQLUser", desired.GetName())
		return s.Client.Create(ctx, desired)
	}

	// Need to copy over annotations as any generated by Google config-connector are required.
	desired.SetAnnotations(actualSQLUser.GetAnnotations())
	reconciliation.SetLastApplied(desired)
	if !reconciliation.NeedsUpdate(s.Log.WithValues("type", "SQLUser"), actualSQLUser, desired) {
		return nil
	}

	// TODO: factor this out into separate method
	// Resource version is required for the update, but needs to be set after
	// the last applied annotation to avoid unnecessary diffs
	resourceVersion, _, err := unstructured.NestedString(actualSQLUser.Object, "metadata", "resourceVersion")
	if err != nil {
		return err
	}
	if err := unstructured.SetNestedField(desired.Object, resourceVersion, "metadata", "resourceVersion"); err != nil {
		return err
	}
	log.Info("Updating CloudSQL user", "SQLUser", desired.GetName())
	return s.Client.Update(ctx, desired)
}

func (s *PostgresStore) reconcileGoogleCloudSQLDatabase(ctx context.Context, statefulService *cloudstate.StatefulService, actual *gcloud.SQLDatabase, instanceName string, databaseName string, log logr.Logger) error {
	desired, err := s.createDesiredGoogleCloudSQLDatabase(statefulService, instanceName, databaseName)
	if err != nil {
		return err
	}
	if actual == nil {
		log.Info("Creating CloudSQL database", "SQLDatabase", databaseName)
		return s.Client.Create(ctx, desired)
	}

	desired.SetAnnotations(actual.GetAnnotations())
	reconciliation.SetLastApplied(desired)
	if !reconciliation.NeedsUpdate(log.WithValues("type", "SQLDatabase"), actual, desired) {
		return nil
	}

	resourceVersion, _, err := unstructured.NestedString(actual.Object, "metadata", "resourceVersion")
	if err != nil {
		return err
	}
	if err := unstructured.SetNestedField(desired.Object, resourceVersion, "metadata", "resourceVersion"); err != nil {
		return err
	}
	log.Info("Updating CloudSQL database", "SQLDatabase", databaseName)
	return s.Client.Update(ctx, desired)
}

func (s *PostgresStore) ReconcileStatefulStore(ctx context.Context, store *cloudstate.StatefulStore) ([]cloudstate.CloudstateCondition, bool, error) {
	spec := store.Spec.Postgres
	if spec == nil {
		return nil, false, errors.New("nil Postgres store")
	}
	host := spec.Host
	if host != "" {
		return nil, false, nil
	}
	if spec.GoogleCloudSQL == nil {
		return nil, false, errors.New("postgres host must not be empty")
	}
	if !s.Config.GoogleCloudSQL.Enabled {
		return []cloudstate.CloudstateCondition{
			{
				Type:    PostgresGoogleCloudSQLInstanceNotReady,
				Status:  corev1.ConditionTrue,
				Reason:  "GoogleCloudSQLSupportNotEnabled",
				Message: "Postgres Google Cloud SQL support is not enabled",
			},
		}, false, nil
	}
	return s.reconcileGoogleCloudSQLInstance(ctx, store)
}

func (s *PostgresStore) reconcileGoogleCloudSQLInstance(ctx context.Context, statefulStore *cloudstate.StatefulStore) ([]cloudstate.CloudstateCondition, bool, error) {
	// Get SQLInstances we currently own
	// Pruning since we allow at most one instance per controller instance
	ownedSQLInstances, err := reconciliation.GetControlledUnstructured(
		ctx, s.Client, statefulStore.Name,
		statefulStore.Namespace, statefulStore.GroupVersionKind(), gcloud.SQLInstanceGVK, true,
	)
	if err != nil {
		s.Log.Error(err, "unable to list owned SQL instances")
		return nil, false, err
	}

	var ownedSQLInstance *unstructured.Unstructured
	if len(ownedSQLInstances) > 0 {
		ownedSQLInstance = &ownedSQLInstances[0]
	}

	obj, err := reconciliation.GetControlledStructured(ctx, s.Client, statefulStore, &corev1.SecretList{})
	if err != nil {
		return nil, false, err
	}
	var ownedSQLCnxSecret *corev1.Secret
	if obj != nil {
		ownedSQLCnxSecret = obj.(*corev1.Secret)
	}

	sqlInstance, err := s.reconcileSQLInstance(ctx, statefulStore, ownedSQLInstance)
	if err != nil {
		return nil, false, fmt.Errorf("unable to reconcile sqlinstance: %w", err)
	}

	updated := false
	if statefulStore.Status.Postgres == nil || statefulStore.Status.Postgres.GoogleCloudSQL == nil ||
		statefulStore.Status.Postgres.GoogleCloudSQL.InstanceName != sqlInstance.GetName() {
		updated = true
		statefulStore.Status.Postgres = &cloudstate.PostgresStoreStatus{
			GoogleCloudSQL: &cloudstate.GoogleCloudSQLPostgresStoreStatus{
				InstanceName: sqlInstance.GetName(),
			},
		}
	}

	if err := s.reconcileSQLCnxSecret(ctx, statefulStore, ownedSQLInstance, ownedSQLCnxSecret); err != nil {
		return nil, false, fmt.Errorf("unable to reconcile client SQL connection secret: %w", err)
	}
	return []cloudstate.CloudstateCondition{
		s.conditionForSQLInstance(sqlInstance),
	}, updated, nil
}

// reconcileSQLInstance reconciles a SQLInstance, setting the owner to statefulStore.  Will create the SQLInstance if
// ownedSQLInstance is nil.  Either way, returns the name of the SQLInstance if and only if the error is nil.
func (s *PostgresStore) reconcileSQLInstance(ctx context.Context,
	statefulStore *cloudstate.StatefulStore, ownedSQLInstance *unstructured.Unstructured) (*gcloud.SQLInstance, error) {
	var actual *unstructured.Unstructured
	if ownedSQLInstance != nil {
		actual = ownedSQLInstance
	}
	desired, err := s.createDesiredSQLInstance(statefulStore, ownedSQLInstance)
	if err != nil {
		return nil, err
	}
	reconciliation.SetLastApplied(desired)
	if actual == nil {
		if err = s.Client.Create(ctx, desired); err != nil {
			return nil, err
		} else {
			return desired, nil
		}
	}
	if !reconciliation.NeedsUpdate(s.Log.WithValues("type", "SQLInstance"), actual, desired) {
		return actual, nil
	}
	// Resource version is required for the update, but needs to be set after
	// the last applied annotation to avoid unnecessary diffs
	resourceVersion, _, _ := unstructured.NestedString(actual.Object, "metadata", "resourceVersion")
	if err := unstructured.SetNestedField(desired.Object, resourceVersion, "metadata", "resourceVersion"); err != nil {
		return nil, err
	}
	// Copy status across as well
	status, found, err := unstructured.NestedMap(actual.Object, "status")
	if err != nil {
		return nil, err
	}
	if found {
		if err := unstructured.SetNestedMap(desired.Object, status, "status"); err != nil {
			return nil, err
		}
	}
	if err = s.Client.Update(ctx, desired); err != nil {
		return nil, err
	}
	return desired, nil
}

// Bundle connection info for SQLInstance in k8s secret.
func (s *PostgresStore) reconcileSQLCnxSecret(ctx context.Context, statefulStore *cloudstate.StatefulStore, sqlInstance *gcloud.SQLInstance, ownedSQLCnxSecret *corev1.Secret) error {
	if sqlInstance == nil {
		return nil // Need a sql instance to proceed
	}

	var actual *corev1.Secret
	if ownedSQLCnxSecret != nil {
		actual = ownedSQLCnxSecret
	}
	desired, err := s.createDesiredSQLCnxSecret(statefulStore, sqlInstance)
	if err != nil {
		return err
	}
	if desired == nil { // sqlInstance isn't ready with info required to create the secret
		return nil
	}
	reconciliation.SetLastApplied(desired)

	if actual == nil {
		if err = s.Client.Create(ctx, desired); err != nil {
			return err
		} else {
			return nil
		}
	}
	if !reconciliation.NeedsUpdate(s.Log.WithValues("type", "Secret"), actual, desired) {
		return nil
	}
	return s.Client.Update(ctx, desired)
}

// createDesiredSQLInstance creates a SQLInstance based on the spec in the StatefulStore.  If name is provided
// it is used as the name of the SQLInstance, otherwise a name is created.
//
// Some fields are immutable once created. (e.g. databaseVersion)  For some of these, if existing is nil (ie. this is the initial
// creation of the object), the desired values are taken from the global config.  If existing is non-nil, the desired values match those
// in the existing object.  This allows us to change how new objects are created but leave existing ones alone.
func (s *PostgresStore) createDesiredSQLInstance(statefulStore *cloudstate.StatefulStore, existing *gcloud.SQLInstance) (*gcloud.SQLInstance, error) {
	// For these properties, use the value in the existing object if it exists.
	var name string
	var dbVersion string
	var dbRegion string
	privateNetworkRef := "projects/" + s.GCP.Project + "/global/networks/" + s.GCP.Network

	// If "databaseVersion" is unspecified in configs or existing, we'll use the GCP default.
	if version := s.Config.GoogleCloudSQL.TypeVersion; version != "" {
		dbVersion = version
	}

	if existing != nil {
		name = existing.GetName()
		if existingDBVersion, found, _ := unstructured.NestedString(existing.UnstructuredContent(), "spec", "databaseVersion"); found {
			dbVersion = existingDBVersion
		}
		if existingDBRegion, found, _ := unstructured.NestedString(existing.UnstructuredContent(), "spec", "region"); found {
			dbRegion = existingDBRegion
		}
		if existingPrivateNetworkRef, found, _ := unstructured.NestedString(
			existing.UnstructuredContent(), "spec", "settings", "ipConfiguration", "privateNetworkRef", "external",
		); found {
			privateNetworkRef = existingPrivateNetworkRef
		}
	}

	// Create a unique name for the SQLInstance if we need to
	if name == "" {
		name = s.createCloudSQLInstanceName(statefulStore)
	}
	sqlInstance := gcloud.MakeSQLInstance(
		map[string]interface{}{
			"metadata": map[string]interface{}{
				"name":      name,
				"namespace": statefulStore.Namespace,
				"labels":    map[string]interface{}{},
			},
			"spec": map[string]interface{}{
				"region":   dbRegion,
				"settings": map[string]interface{}{},
			},
		},
	)
	// c := runtime.DefaultUnstructuredConverter
	// c.FromUnstructured(sqlInstance.UnstructuredContent(), )
	sqlInstanceSpec := sqlInstance.UnstructuredContent()["spec"].(map[string]interface{})
	sqlInstanceSpecSettings := sqlInstanceSpec["settings"].(map[string]interface{})

	// Wire the instance with a private IP in our network
	sqlInstanceSpecSettings["ipConfiguration"] = map[string]interface{}{
		"ipv4Enabled": false, // This flag is more like hasPublicIP
		"privateNetworkRef": map[string]string{
			"external": privateNetworkRef,
		},
	}

	// Assume requested values are valid for now. Will validate in a validating admission webhook at some point
	if dbVersion != "" {
		sqlInstanceSpec["databaseVersion"] = dbVersion
	}
	cloudSQL := statefulStore.Spec.Postgres.GoogleCloudSQL
	if cloudSQL.HighAvailability {
		// Based on https://cloud.google.com/config-connector/docs/reference/resources#sqlinstance
		sqlInstanceSpecSettings["availabilityType"] = "REGIONAL"
		// Do we want to change the name to indicate HA?
	}
	if !cloudSQL.Storage.Capacity.IsZero() {
		sqlInstanceSpecSettings["diskSize"] = cloudSQL.Storage.Capacity.Value()
	}
	if cloudSQL.Storage.AutomaticIncrease {
		sqlInstanceSpecSettings["diskAutoresize"] = true
	}
	if cloudSQL.Storage.Type != "" {
		sqlInstanceSpecSettings["diskType"] = cloudSQL.Storage.Type
	}

	// Use cores and memory values to determine a machine type (aka tier)
	// Assuming values have already been validated.
	cores := s.Config.GoogleCloudSQL.DefaultCores
	if cloudSQL.Cores > 0 {
		cores = cloudSQL.Cores
	}
	memory := s.Config.GoogleCloudSQL.DefaultMemory
	if !cloudSQL.Memory.IsZero() {
		result := make([]byte, 0, 18)
		number, _ := cloudSQL.Memory.CanonicalizeBytes(result)
		// _ = suffix // Could check that suffix is "Mi"...
		memory = string(number)
	}
	var tier = "db-custom-" + strconv.FormatInt(int64(cores), 10) + "-" + memory
	sqlInstanceSpecSettings["tier"] = tier

	// TODO: implement this other feature:
	// Don't see how to do this with config-connector atm...
	// storage:
	//   description: The storage configuration.
	//   properties:
	//     automaticIncreaseLimit:
	//       description: A limit to how high the capacity may be automatically
	//         increased. A value of 0 means no limit. The default is 0.
	//       type: string

	//	sqlInstanceRes := schema.GroupVersionResource{Group: "sql.cnrm.cloud.google.com", Version: "v1beta1", Resource: "sqlinstances"}
	sqlInstance.SetLabels(reconciliation.SetCommonLabels(statefulStore.Name, sqlInstance.GetLabels()))
	if err := ctrl.SetControllerReference(statefulStore, sqlInstance, s.Scheme); err != nil {
		return nil, fmt.Errorf("unable to set owner: %w", err)
	}
	return sqlInstance, nil
}

func (s *PostgresStore) createCloudSQLInstanceName(statefulStore *cloudstate.StatefulStore) string {
	name := statefulStore.Spec.Postgres.GoogleCloudSQL.Name
	if name == "" {
		name = statefulStore.Name
	}
	return name
}

func (s *PostgresStore) createDesiredSQLCnxSecret(statefulStore *cloudstate.StatefulStore, sqlInstance *gcloud.SQLInstance) (*corev1.Secret, error) {
	if sqlInstance == nil {
		return nil, errors.New("sqlInstance cannot be nil")
	}

	secretName := postgresCnxSecretPrefix + statefulStore.Name
	// Instance not ready if any of these are not found.
	instanceIP, found, err := unstructured.NestedString(sqlInstance.Object, "status", "privateIpAddress")
	if !found {
		return nil, err
	}
	connectionName, found, err := unstructured.NestedString(sqlInstance.Object, "status", "connectionName")
	if !found {
		return nil, err
	}

	desiredSecret := &corev1.Secret{
		ObjectMeta: metav1.ObjectMeta{
			Name:      secretName,
			Namespace: statefulStore.Namespace,
			Labels:    make(map[string]string),
		},
		Data: map[string][]byte{
			"instanceIP":     []byte(instanceIP),
			"connectionName": []byte(connectionName),
		},
	}

	desiredSecret.SetLabels(reconciliation.SetCommonLabels(secretName, desiredSecret.GetLabels()))
	if err := ctrl.SetControllerReference(statefulStore, desiredSecret, s.Scheme); err != nil {
		return nil, fmt.Errorf("unable to set owner: %w", err)
	}
	return desiredSecret, nil
}
