package stores

import (
	"encoding/base64"
	"fmt"
	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/config"
	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/reconciliation"
	"github.com/go-logr/logr"
	"golang.org/x/net/context"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/builder"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/handler"
	"sigs.k8s.io/controller-runtime/pkg/source"
	"strconv"

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
	Gcp    *config.GcpConfig
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

	PostgresGoogleCloudSqlInstanceNotReady cloudstate.CloudstateConditionType = "CloudSqlInstanceNotReady"
	PostgresGoogleCloudSqlDatabaseNotReady cloudstate.CloudstateConditionType = "CloudSqlDatabaseNotReady"
	PostgresGoogleCloudSqlUserNotReady     cloudstate.CloudstateConditionType = "CloudSqlUserNotReady"

	postgresCnxSecretPrefix   = "postgres-cnx-"
	postgresCredsSecretPrefix = "postgres-creds-"
)

func (p *PostgresStore) SetupWithStatefulServiceController(builder *builder.Builder) error {

	if p.Config.GoogleCloudSql.Enabled {
		uSqlDatabase := gcloud.NewSQLDatabase()
		uSqlUser := gcloud.NewSQLUser()

		builder.
			Watches(&source.Kind{Type: uSqlDatabase}, &handler.EnqueueRequestForOwner{
				IsController: true,
				OwnerType:    &cloudstate.StatefulService{}}).
			Watches(&source.Kind{Type: uSqlUser}, &handler.EnqueueRequestForOwner{
				IsController: true,
				OwnerType:    &cloudstate.StatefulService{}})
	}

	return nil
}

func (p *PostgresStore) SetupWithStatefulStoreController(builder *builder.Builder) error {

	if p.Config.GoogleCloudSql.Enabled {
		uSqlInstance := gcloud.NewSQLInstance()

		builder.
			Watches(&source.Kind{Type: uSqlInstance}, &handler.EnqueueRequestForOwner{
				IsController: true,
				OwnerType:    &cloudstate.StatefulStore{}}).
			Owns(&corev1.Secret{})

	}

	return nil
}

func (c *PostgresStore) InjectPodStoreConfig(
	ctx context.Context,
	name string,
	namespace string,
	pod *corev1.Pod,
	cloudstateSidecarContainer *corev1.Container,
	store *cloudstate.StatefulStore,
) error {

	cloudstateSidecarContainer.Image = c.Config.Image

	spec := store.Spec.Postgres

	if spec == nil {
		return fmt.Errorf("nil Postgres store")
	}

	host := spec.Host
	if host != "" {
		c.injectUnmanagedStoreConfig(cloudstateSidecarContainer, host, spec, pod)
	} else if spec.GoogleCloudSQL != nil {
		if !c.Config.GoogleCloudSql.Enabled {
			return fmt.Errorf("postgres Google Cloud SQL support is not enabled")
		}
		if err := c.injectGoogleCloudSqlStoreConfig(name, namespace, cloudstateSidecarContainer, store, pod); err != nil {
			return err
		}
	} else {
		return fmt.Errorf("postgres host must not be empty")
	}

	return nil
}

func (c *PostgresStore) ReconcileStatefulService(
	ctx context.Context,
	statefulService *cloudstate.StatefulService,
	deployment *appsv1.Deployment,
	store *cloudstate.StatefulStore,
) ([]cloudstate.CloudstateCondition, error) {

	log := c.Log.WithValues("statefulservice", statefulService.Namespace+"/"+statefulService.Name)
	spec := store.Spec.Postgres

	if spec == nil {
		return nil, fmt.Errorf("nil Postgres store")
	}

	host := spec.Host
	if host != "" {
		c.reconcileUnmanagedDeploymentStoreConfig(spec, statefulService, deployment)
	} else if spec.GoogleCloudSQL != nil {
		if !c.Config.GoogleCloudSql.Enabled {
			return []cloudstate.CloudstateCondition{
				{
					Type:    PostgresGoogleCloudSqlInstanceNotReady,
					Status:  corev1.ConditionTrue,
					Reason:  "GoogleCloudSQLSupportNotEnabled",
					Message: "Postgres Google Cloud SQL support is not enabled",
				},
			}, nil
		}
		return c.reconcileGoogleCloudSqlDeployment(ctx, statefulService, deployment, store, log)
	} else {
		return nil, fmt.Errorf("postgres host must not be empty")
	}

	return nil, nil
}

func (c *PostgresStore) reconcileUnmanagedDeploymentStoreConfig(
	postgresStore *cloudstate.PostgresStore,
	statefulService *cloudstate.StatefulService,
	deployment *appsv1.Deployment,
) {
	if statefulService.Spec.StoreConfig.Database != "" {
		deployment.Spec.Template.Annotations[CloudstatePostgresDatabaseAnnotation] = statefulService.Spec.StoreConfig.Database
	}

	if statefulService.Spec.StoreConfig.Secret != nil {
		deployment.Spec.Template.Annotations[CloudstatePostgresSecretAnnotation] = statefulService.Spec.StoreConfig.Secret.Name
	}
}

func (c *PostgresStore) injectUnmanagedStoreConfig(cloudstateSidecarContainer *corev1.Container, host string, spec *cloudstate.PostgresStore, pod *corev1.Pod) {
	appendEnvVar(cloudstateSidecarContainer, postgresServiceEnvVar, host)
	if spec.Port != 0 {
		appendEnvVar(cloudstateSidecarContainer, postgresPortEnvVar, strconv.Itoa(int(spec.Port)))
	}
	var secret *corev1.LocalObjectReference
	if pod.Annotations[CloudstatePostgresSecretAnnotation] != "" {
		secret = &corev1.LocalObjectReference{
			Name: pod.Annotations[CloudstatePostgresSecretAnnotation],
		}
	} else if spec.Credentials != nil {
		secret = spec.Credentials.Secret
	}
	databaseKey := "database"
	usernameKey := "username"
	passwordKey := "password"
	if spec.Credentials != nil {
		if spec.Credentials.DatabaseKey != "" {
			databaseKey = spec.Credentials.DatabaseKey
		}
		if spec.Credentials.UsernameKey != "" {
			usernameKey = spec.Credentials.UsernameKey
		}
		if spec.Credentials.PasswordKey != "" {
			passwordKey = spec.Credentials.PasswordKey
		}
	}
	// Keyspace config
	if pod.Annotations[CloudstatePostgresDatabaseAnnotation] != "" {
		appendEnvVar(cloudstateSidecarContainer, postgresDatabaseEnvVar, pod.Annotations[CloudstatePostgresDatabaseAnnotation])
	} else if secret != nil {
		appendOptionalSecretEnvVar(cloudstateSidecarContainer, postgresDatabaseEnvVar, *secret, databaseKey)
	}
	// username/password config
	if secret != nil {
		appendSecretEnvVar(cloudstateSidecarContainer, postgresUsernameEnvVar, *secret, usernameKey)
		appendSecretEnvVar(cloudstateSidecarContainer, postgresPasswordEnvVar, *secret, passwordKey)
	}
}

func (p *PostgresStore) injectGoogleCloudSqlStoreConfig(name string, namespace string, cloudstateSidecarContainer *corev1.Container, store *cloudstate.StatefulStore, pod *corev1.Pod) error {

	databaseName := pod.Annotations[CloudstatePostgresDatabaseAnnotation]
	secretName := pod.Annotations[CloudstatePostgresSecretAnnotation]
	cnxSecretName := postgresCnxSecretPrefix + store.Name

	// We don't really want to create databases in a mutating webhook, so rather than trying to do that, we just
	// require an operator to already have done that
	if secretName == "" || databaseName == "" {
		return fmt.Errorf("a Google Cloud SQL store requires using a StatefulService to reconcile it")
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

func (p *PostgresStore) reconcileGoogleCloudSqlDeployment(
	ctx context.Context,
	statefulService *cloudstate.StatefulService,
	deployment *appsv1.Deployment,
	store *cloudstate.StatefulStore,
	log logr.Logger,
) ([]cloudstate.CloudstateCondition, error) {

	ownedSqlDatabases, err := reconciliation.GetControlledUnstructured(ctx, p.Client, statefulService.Name, statefulService.Namespace,
		statefulService.GroupVersionKind(), gcloud.SQLDatabaseGVK, true)
	if err != nil {
		p.Log.Error(err, "unable to list owned SQL database(s)")
		return nil, err
	}
	var ownedSqlDatabase *gcloud.SQLDatabase = nil
	if len(ownedSqlDatabases) > 0 {
		ownedSqlDatabase = &ownedSqlDatabases[0]
	}
	ownedSqlUsers, err := reconciliation.GetControlledUnstructured(ctx, p.Client, statefulService.Name, statefulService.Namespace,
		statefulService.GroupVersionKind(), gcloud.SQLUserGVK, true)
	if err != nil {
		p.Log.Error(err, "unable to list owned SQL users")
		return nil, err
	}
	var ownedSqlUser *gcloud.SQLUser = nil
	if len(ownedSqlUsers) > 0 {
		ownedSqlUser = &ownedSqlUsers[0]
	}

	secretObj, err := reconciliation.GetControlledStructured(ctx, p.Client, statefulService, &corev1.SecretList{})
	if err != nil {
		return nil, err
	}
	var ownedSqlUserSecret *corev1.Secret
	if secretObj != nil {
		ownedSqlUserSecret = secretObj.(*corev1.Secret)
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
		instanceName = p.createCloudSQLInstanceName(store)
		conditions = append(conditions, p.conditionForSQLInstance(nil))
	}
	// Create SQLDatabase, SQLUser, and secret (password)
	if err := p.reconcileGoogleCloudSQLDatabase(ctx, statefulService, ownedSqlDatabase, instanceName, databaseName, log); err != nil {
		return nil, fmt.Errorf("unable to reconcile sqldatabase: %w", err)
	}
	if err := p.reconcileGoogleCloudSQLUser(ctx, statefulService, ownedSqlUser, ownedSqlUserSecret, instanceName, secretName, log); err != nil {
		return nil, fmt.Errorf("unable to reconcile sqluser: %w", err)
	}

	// Add the relevant annotations to the deployment
	deployment.Annotations[CloudstatePostgresDatabaseAnnotation] = databaseName
	deployment.Annotations[CloudstatePostgresSecretAnnotation] = secretName

	// Add conditions for managed resources
	conditions = append(conditions, p.conditionForSQLDatabase(ownedSqlDatabase), p.conditionForSQLUser(ownedSqlUser))
	return conditions, nil
}

func (p *PostgresStore) createDesiredGoogleCloudSQLDatabase(statefulService *cloudstate.StatefulService,
	instanceName string, databaseName string) (*gcloud.SQLDatabase, error) {

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
	if err := ctrl.SetControllerReference(statefulService, sqlDatabase, p.Scheme); err != nil {
		return nil, fmt.Errorf("unable to set owner: %w", err)
	}
	return sqlDatabase, nil
}

func (p *PostgresStore) createDesiredGoogleCloudSQLUser(statefulService *cloudstate.StatefulService,
	ownedSqlUserSecret *corev1.Secret, instanceName string) (*gcloud.SQLUser, error) {

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
					//"value": "changeMeSomehow",  // plain password
					"valueFrom": map[string]interface{}{
						"secretKeyRef": map[string]string{
							"name": ownedSqlUserSecret.Name,
							"key":  "password",
						},
					},
				},
			},
		},
	)

	sqlUser.SetLabels(reconciliation.SetCommonLabels(statefulService.Name, sqlUser.GetLabels()))
	if err := ctrl.SetControllerReference(statefulService, sqlUser, p.Scheme); err != nil {
		return nil, fmt.Errorf("unable to set owner: %w", err)
	}
	return sqlUser, nil
}

func (p *PostgresStore) createDesiredGoogleCloudSQLUserSecret(statefulService *cloudstate.StatefulService, secretName string) (*corev1.Secret, error) {

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
	if err := ctrl.SetControllerReference(statefulService, desiredSecret, p.Scheme); err != nil {
		return nil, fmt.Errorf("unable to set owner: %w", err)
	}
	return desiredSecret, nil
}

func (p *PostgresStore) conditionForSQLDatabase(db *gcloud.SQLDatabase) cloudstate.CloudstateCondition {
	condition := cloudstate.CloudstateCondition{
		Type: PostgresGoogleCloudSqlDatabaseNotReady,
	}
	if db != nil {
		if status, ok := db.UnstructuredContent()["status"]; ok {
			// There _has_ to be a better way to do this stuff...
			sqlDatabaseStatus := status.(map[string]interface{}) //["conditions"][0]["type"]
			conditions := sqlDatabaseStatus["conditions"]        //.([]map[string]interface{})
			sqlDBCondition := conditions.([]interface{})[0]
			sqlDBCondType := sqlDBCondition.(map[string]interface{})["type"].(string)
			sqlDBCondStatus := sqlDBCondition.(map[string]interface{})["status"].(string) //corev1.ConditionStatus
			sqlDBCondReason := sqlDBCondition.(map[string]interface{})["reason"].(string)
			sqlDBCondMessage := sqlDBCondition.(map[string]interface{})["message"].(string)
			if sqlDBCondType == "Ready" && sqlDBCondStatus == "True" {
				condition.Status = corev1.ConditionFalse // SQLDatabase uses Ready while StatefulService uses NotReady so status is opposite
				condition.Reason = "CloudSqlDatabaseReady"
				condition.Message = "The Google Cloud SQL database has been provisioned"
			} else {
				condition.Status = corev1.ConditionTrue // SQLDatabase uses Ready while StatefulService uses NotReady so status is opposite
				condition.Reason = "CloudSqlDatabaseUnavailable"
				condition.Message = fmt.Sprintf("The Google Cloud SQL database is not yet ready: %s: %s", sqlDBCondReason, sqlDBCondMessage)
			}
		} else {
			condition.Status = corev1.ConditionTrue // SQLDatabase uses Ready while StatefulService uses NotReady so status is opposite
			condition.Reason = "CloudSqlDatabaseUnknownStatus"
			condition.Message = fmt.Sprintf("The Google Cloud SQL database has unknown status")
		}
	} else {
		condition.Status = corev1.ConditionTrue // SQLDatabase uses Ready while StatefulService uses NotReady so status is opposite
		condition.Reason = "CloudSqlDatabaseNotCreated"
		condition.Message = fmt.Sprintf("The Google Cloud SQL database as not been created")
	}

	return condition
}

func (p *PostgresStore) conditionForSQLUser(user *gcloud.SQLUser) cloudstate.CloudstateCondition {
	condition := cloudstate.CloudstateCondition{
		Type: PostgresGoogleCloudSqlUserNotReady,
	}
	if user != nil {
		if status, ok := user.UnstructuredContent()["status"]; ok {
			// There _has_ to be a better way to do this stuff...
			sqlUserStatus := status.(map[string]interface{}) //["conditions"][0]["type"]
			conditions := sqlUserStatus["conditions"]        //.([]map[string]interface{})
			sqlUserCondition := conditions.([]interface{})[0]
			sqlUserCondType := sqlUserCondition.(map[string]interface{})["type"].(string)
			sqlUserCondStatus := sqlUserCondition.(map[string]interface{})["status"].(string) //corev1.ConditionStatus
			sqlUserCondReason := sqlUserCondition.(map[string]interface{})["reason"].(string)
			sqlUserCondMessage := sqlUserCondition.(map[string]interface{})["message"].(string)
			if sqlUserCondType == "Ready" && sqlUserCondStatus == "True" {
				condition.Status = corev1.ConditionFalse // SQLUser uses Ready while StatefulService uses NotReady so status is opposite
				condition.Reason = "CloudSqlUserReady"
				condition.Message = "The Google Cloud SQL user has been provisioned"
			} else {
				condition.Status = corev1.ConditionTrue // SQLUser uses Ready while StatefulService uses NotReady so status is opposite
				condition.Reason = "CloudSqlUserUnavailable"
				condition.Message = fmt.Sprintf("The Google Cloud SQL user is not yet ready: %s: %s", sqlUserCondReason, sqlUserCondMessage)
			}
		} else {
			condition.Status = corev1.ConditionTrue // SQLUser uses Ready while StatefulService uses NotReady so status is opposite
			condition.Reason = "CloudSqlUserUnknownStatus"
			condition.Message = fmt.Sprintf("The Google Cloud SQL user has unknown status")
		}
	} else {
		condition.Status = corev1.ConditionTrue // SQLUser uses Ready while StatefulService uses NotReady so status is opposite
		condition.Reason = "CloudSqlUserNotCreated"
		condition.Message = fmt.Sprintf("The Google Cloud SQL user as not been created")
	}

	return condition
}

func (p *PostgresStore) conditionForSQLInstance(instance *gcloud.SQLInstance) cloudstate.CloudstateCondition {
	condition := cloudstate.CloudstateCondition{
		Type: PostgresGoogleCloudSqlInstanceNotReady,
	}
	if instance == nil {
		condition.Status = corev1.ConditionTrue
		condition.Reason = "CloudSqlInstanceNotCreated"
		condition.Message = "The Google Cloud SQL instance has not yet been created"
	} else {
		// is SQLInstance ready?
		// There _has_ to be a better way to do this...
		sqlIContent := instance.UnstructuredContent()
		if sqlIStatusIface, ok := sqlIContent["status"]; !ok {
			condition.Status = corev1.ConditionTrue
			condition.Reason = "CloudSqlInstanceUnknownStatus"
			condition.Message = fmt.Sprintf("The Google Cloud SQL instance has unknown status")
		} else {
			sqlIStatus := sqlIStatusIface.(map[string]interface{})
			conditions := sqlIStatus["conditions"] //.([]map[string]interface{})
			sqlICondition := conditions.([]interface{})[0]
			sqlICondType := sqlICondition.(map[string]interface{})["type"].(string)
			sqlICondStatus := sqlICondition.(map[string]interface{})["status"].(string) //corev1.ConditionStatus
			sqlICondReason := sqlICondition.(map[string]interface{})["reason"].(string)
			sqlICondMessage := sqlICondition.(map[string]interface{})["message"].(string)

			if sqlICondType != "Ready" { // some type we don't know about
				condition.Status = corev1.ConditionTrue
				condition.Reason = "CloudSqlInstanceNotReady"
				condition.Message = fmt.Sprintf("The Google Cloud SQL has status: %s:%s, %s: %s",
					sqlICondType, sqlICondStatus, sqlICondReason, sqlICondMessage)
			} else if sqlICondStatus != "True" {
				condition.Status = corev1.ConditionTrue
				condition.Reason = "CloudSqlInstanceNotReady"
				condition.Message = fmt.Sprintf("The Google Cloud SQL instance has status: %s -- %s", sqlICondReason, sqlICondMessage)
			} else {
				condition.Status = corev1.ConditionFalse
				condition.Reason = "CloudSqlInstanceReady"
				condition.Message = "The Google Cloud SQL instance is ready"
			}
		}
	}

	return condition
}

func (p *PostgresStore) reconcileGoogleCloudSQLUser(
	ctx context.Context,
	statefulService *cloudstate.StatefulService,
	actualSqlUser *gcloud.SQLUser,
	actualSecret *corev1.Secret,
	instanceName string,
	secretName string,
	log logr.Logger,
) error {
	if actualSecret == nil {
		desiredSecret, err := p.createDesiredGoogleCloudSQLUserSecret(statefulService, secretName)
		if err != nil {
			return err
		}
		log.Info("Creating secret for CloudSQL user", "secret", secretName)
		if err := p.Client.Create(ctx, desiredSecret); err != nil {
			return err
		}
		actualSecret = desiredSecret
	}

	desired, err := p.createDesiredGoogleCloudSQLUser(statefulService, actualSecret, instanceName)
	if err != nil {
		return err
	}

	if actualSqlUser == nil {
		reconciliation.SetLastApplied(desired)
		log.Info("Creating CloudSQL user", "SQLUser", desired.GetName())
		return p.Client.Create(ctx, desired)
	}

	// Need to copy over annotations as any generated by Google config-connector are required.
	desired.SetAnnotations(actualSqlUser.GetAnnotations())
	reconciliation.SetLastApplied(desired)

	if !reconciliation.NeedsUpdate(p.Log.WithValues("type", "SQLUser"), actualSqlUser, desired) {
		return nil
	}

	// Todo: factor this out into separate method
	// Resource version is required for the update, but needs to be set after
	// the last applied annotation to avoid unnecessary diffs
	resourceVersion, _, _ := unstructured.NestedString(actualSqlUser.Object, "metadata", "resourceVersion")
	if err := unstructured.SetNestedField(desired.Object, resourceVersion, "metadata", "resourceVersion"); err != nil {
		return err
	}

	log.Info("Updating CloudSQL user", "SQLUser", desired.GetName())
	if err = p.Client.Update(ctx, desired); err != nil {
		return err
	}
	return nil
}

func (p *PostgresStore) reconcileGoogleCloudSQLDatabase(
	ctx context.Context,
	statefulService *cloudstate.StatefulService,
	actual *gcloud.SQLDatabase,
	instanceName string,
	databaseName string,
	log logr.Logger,
) error {
	desired, err := p.createDesiredGoogleCloudSQLDatabase(statefulService, instanceName, databaseName)
	if err != nil {
		return err
	}

	if actual == nil {
		log.Info("Creating CloudSQL database", "SQLDatabase", databaseName)
		if err = p.Client.Create(ctx, desired); err != nil {
			return err
		} else {
			return nil
		}
	}

	desired.SetAnnotations(actual.GetAnnotations())
	reconciliation.SetLastApplied(desired)

	if !reconciliation.NeedsUpdate(log.WithValues("type", "SQLDatabase"), actual, desired) {
		return nil
	}

	resourceVersion, _, _ := unstructured.NestedString(actual.Object, "metadata", "resourceVersion")
	if err := unstructured.SetNestedField(desired.Object, resourceVersion, "metadata", "resourceVersion"); err != nil {
		return err
	}

	log.Info("Updating CloudSQL database", "SQLDatabase", databaseName)
	if err = p.Client.Update(ctx, desired); err != nil {
		return err
	}
	return nil
}

func (p *PostgresStore) ReconcileStatefulStore(ctx context.Context, store *cloudstate.StatefulStore) ([]cloudstate.CloudstateCondition, bool, error) {
	spec := store.Spec.Postgres

	if spec == nil {
		return nil, false, fmt.Errorf("nil Postgres store")
	}

	host := spec.Host
	if host != "" {
		return nil, false, nil
	} else if spec.GoogleCloudSQL != nil {
		if !p.Config.GoogleCloudSql.Enabled {
			return []cloudstate.CloudstateCondition{
				{
					Type:    PostgresGoogleCloudSqlInstanceNotReady,
					Status:  corev1.ConditionTrue,
					Reason:  "GoogleCloudSQLSupportNotEnabled",
					Message: "Postgres Google Cloud SQL support is not enabled",
				},
			}, false, nil
		}
		return p.reconcileGoogleCloudSQLInstance(ctx, store)
	} else {
		return nil, false, fmt.Errorf("postgres host must not be empty")
	}

}

func (p *PostgresStore) reconcileGoogleCloudSQLInstance(ctx context.Context, statefulStore *cloudstate.StatefulStore) ([]cloudstate.CloudstateCondition, bool, error) {

	// Get SQLInstances we currently own
	// Pruning since we allow at most one instance per controller instance
	ownedSqlInstances, err := reconciliation.GetControlledUnstructured(ctx, p.Client, statefulStore.Name,
		statefulStore.Namespace, statefulStore.GroupVersionKind(), gcloud.SQLInstanceGVK, true)

	if err != nil {
		p.Log.Error(err, "unable to list owned SQL instances")
		return nil, false, err
	}
	var ownedSqlInstance *unstructured.Unstructured = nil
	if len(ownedSqlInstances) > 0 {
		ownedSqlInstance = &ownedSqlInstances[0]
	}

	obj, err := reconciliation.GetControlledStructured(ctx, p.Client, statefulStore, &corev1.SecretList{})
	if err != nil {
		return nil, false, err
	}
	var ownedSQLCnxSecret *corev1.Secret
	if obj != nil {
		ownedSQLCnxSecret = obj.(*corev1.Secret)
	}

	sqlInstance, err := p.reconcileSQLInstance(ctx, statefulStore, ownedSqlInstance)
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

	if err := p.reconcileSQLCnxSecret(ctx, statefulStore, ownedSqlInstance, ownedSQLCnxSecret); err != nil {
		return nil, false, fmt.Errorf("unable to reconcile client SQL connection secret: %w", err)
	}

	return []cloudstate.CloudstateCondition{
		p.conditionForSQLInstance(sqlInstance),
	}, updated, nil
}

// reconcileSQLInstance reconciles a SQLInstance, setting the owner to statefulStore.  Will create the SQLInstance if
// ownedSqlInstance is nil.  Either way, returns the name of the SQLInstance if and only if the error is nil.
func (p *PostgresStore) reconcileSQLInstance(
	ctx context.Context,
	statefulStore *cloudstate.StatefulStore,
	ownedSqlInstance *unstructured.Unstructured,
) (*gcloud.SQLInstance, error) {
	var actual *unstructured.Unstructured
	if ownedSqlInstance != nil {
		actual = ownedSqlInstance
	}

	desired, err := p.createDesiredSQLInstance(statefulStore, ownedSqlInstance)
	if err != nil {
		return nil, err
	}

	reconciliation.SetLastApplied(desired)

	if actual == nil {
		if err = p.Client.Create(ctx, desired); err != nil {
			return nil, err
		} else {
			return desired, nil
		}
	}

	if !reconciliation.NeedsUpdate(p.Log.WithValues("type", "SQLInstance"), actual, desired) {
		//log.V(1).Info("No change in SQLInstance")
		return actual, nil
	}

	// Resource version is required for the update, but needs to be set after
	// the last applied annotation to avoid unnecessary diffs
	resourceVersion, _, _ := unstructured.NestedString(actual.Object, "metadata", "resourceVersion")
	if err := unstructured.SetNestedField(desired.Object, resourceVersion, "metadata", "resourceVersion"); err != nil {
		return nil, err
	}
	// Copy status across as well
	status, found, _ := unstructured.NestedMap(actual.Object, "status")
	if found {
		if err := unstructured.SetNestedMap(desired.Object, status, "status"); err != nil {
			return nil, err
		}
	}

	if err = p.Client.Update(ctx, desired); err != nil {
		return nil, err
	}
	return desired, nil
}

// Bundle connection info for SQLInstance in k8s secret
func (p *PostgresStore) reconcileSQLCnxSecret(
	ctx context.Context,
	statefulStore *cloudstate.StatefulStore,
	sqlInstance *gcloud.SQLInstance,
	ownedSQLCnxSecret *corev1.Secret,
) error {
	var actual *corev1.Secret
	if ownedSQLCnxSecret != nil {
		actual = ownedSQLCnxSecret
	}

	if sqlInstance == nil {
		return nil // Need a sql instance to proceed
	}

	desired, err := p.createDesiredSQLCnxSecret(statefulStore, sqlInstance)
	if desired == nil { // sqlInstance isn't ready with info required to create the secret
		return nil
	}
	if err != nil {
		return err
	}
	reconciliation.SetLastApplied(desired)

	if actual == nil {
		if err = p.Client.Create(ctx, desired); err != nil {
			return err
		} else {
			return nil
		}
	}

	if !reconciliation.NeedsUpdate(p.Log.WithValues("type", "Secret"), actual, desired) {
		return nil
	}

	if err = p.Client.Update(ctx, desired); err != nil {
		return err
	}
	return nil
}

// createDesiredSQLInstance creates a SQLInstance based on the spec in the StatefulStore.  If name is provided
// it is used as the name of the SQLInstance, otherwise a name is created.
//
// Some fields are immutable once created. (e.g. databaseVersion)  For some of these, if existing is nil (ie. this is the initial
// creation of the object), the desired values are taken from the global config.  If existing is non-nil, the desired values match those
// in the existing object.  This allows us to change how new objects are created but leave existing ones alone.
func (p *PostgresStore) createDesiredSQLInstance(statefulStore *cloudstate.StatefulStore, existing *gcloud.SQLInstance) (*gcloud.SQLInstance, error) {

	// For these properties, use the value in the existing object if it exists.
	var name string
	var dbVersion string
	var dbRegion string
	privateNetworkRef := "projects/" + p.Gcp.Project + "/global/networks/" + p.Gcp.Network

	// If "databaseVersion" is unspecified in configs or existing, we'll use the GCP default.
	if p.Config.GoogleCloudSql.TypeVersion != "" {
		dbVersion = p.Config.GoogleCloudSql.TypeVersion
	}
	if existing != nil {
		name = existing.GetName()
		existingDBVersion, found, _ := unstructured.NestedString(existing.UnstructuredContent(), "spec", "databaseVersion")
		if found {
			dbVersion = existingDBVersion
		}
		existingDBRegion, found, _ := unstructured.NestedString(existing.UnstructuredContent(), "spec", "region")
		if found {
			dbRegion = existingDBRegion
		}
		existingPrivateNetworkRef, found, _ := unstructured.NestedString(existing.UnstructuredContent(), "spec", "settings", "ipConfiguration", "privateNetworkRef", "external")
		if found {
			privateNetworkRef = existingPrivateNetworkRef
		}
	}

	// Create a unique name for the SQLInstance if we need to
	if name == "" {
		name = p.createCloudSQLInstanceName(statefulStore)
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
	sqlInstanceSpec := sqlInstance.UnstructuredContent()["spec"].(map[string]interface{})
	sqlInstanceSpecSettings := sqlInstanceSpec["settings"].(map[string]interface{})

	// Wire the instance with a private IP in our network
	sqlInstanceSpecSettings["ipConfiguration"] = map[string]interface{}{
		"ipv4Enabled": false, // This flag is more like hasPublicIP
		"privateNetworkRef": map[string]string{
			"external": privateNetworkRef,
		},
	}

	// Assume requested values are valid for now.  Will validate in a validating admission webhook at some point

	if dbVersion != "" {
		sqlInstanceSpec["databaseVersion"] = dbVersion
	}
	cloudSql := statefulStore.Spec.Postgres.GoogleCloudSQL
	if cloudSql.HighAvailability {
		// Based on https://cloud.google.com/config-connector/docs/reference/resources#sqlinstance
		sqlInstanceSpecSettings["availabilityType"] = "REGIONAL"
		// Do we want to change the name to indicate HA?
	}
	if !cloudSql.Storage.Capacity.IsZero() {
		sqlInstanceSpecSettings["diskSize"] = cloudSql.Storage.Capacity.Value()
	}
	if cloudSql.Storage.AutomaticIncrease {
		sqlInstanceSpecSettings["diskAutoresize"] = true
	}
	if cloudSql.Storage.Type != "" {
		sqlInstanceSpecSettings["diskType"] = cloudSql.Storage.Type
	}

	// Use cores and memory values to determine a machine type (aka tier)
	// Assuming values have already been validated.
	cores := p.Config.GoogleCloudSql.DefaultCores
	if cloudSql.Cores > 0 {
		cores = cloudSql.Cores
	}
	memory := p.Config.GoogleCloudSql.DefaultMemory
	if !cloudSql.Memory.IsZero() {
		result := make([]byte, 0, 18)
		number, _ := cloudSql.Memory.CanonicalizeBytes(result)
		//_ = suffix // Could check that suffix is "Mi"...
		memory = string(number)
	}
	var tier = "db-custom-" + strconv.FormatInt(int64(cores), 10) + "-" + memory
	sqlInstanceSpecSettings["tier"] = tier

	// Todo: implement this other feature:
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
	if err := ctrl.SetControllerReference(statefulStore, sqlInstance, p.Scheme); err != nil {
		return nil, fmt.Errorf("unable to set owner: %w", err)
	}

	return sqlInstance, nil
}

func (p *PostgresStore) createCloudSQLInstanceName(statefulStore *cloudstate.StatefulStore) string {
	name := statefulStore.Spec.Postgres.GoogleCloudSQL.Name
	if name == "" {
		name = statefulStore.Name
	}
	return name
}

func (p *PostgresStore) createDesiredSQLCnxSecret(statefulStore *cloudstate.StatefulStore, sqlInstance *gcloud.SQLInstance) (*corev1.Secret, error) {
	if sqlInstance == nil {
		return nil, fmt.Errorf("sqlInstance cannot be nil")
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
	if err := ctrl.SetControllerReference(statefulStore, desiredSecret, p.Scheme); err != nil {
		return nil, fmt.Errorf("unable to set owner: %w", err)
	}
	return desiredSecret, nil
}
