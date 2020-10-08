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

package v1alpha1

import (
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// StatefulStoreSpec defines the desired state of StatefulStore.
type StatefulStoreSpec struct {
	// InMemory store will keep everything in memory. This is the simplest
	// option to get started, but must not be used in production.
	// +optional
	InMemory bool `json:"inMemory,omitempty"`

	// Postgres will store state in Postgres.
	// +optional
	Postgres *PostgresStore `json:"postgres,omitempty"`

	// SpannerStore uses a Spanner store managed by the Cloudstate infrastructure.
	// +optional
	Spanner *SpannerStore `json:"spanner,omitempty"`

	// Cassandra
	// +optional
	Cassandra *CassandraStore `json:"cassandra,omitempty"`
}

// PostgresStore represents a Postgres instance.
type PostgresStore struct {
	// Host to connect to.
	// +kubebuilder:validation:MinLength=1
	// +optional
	Host string `json:"host,omitempty"`

	// Port to connect to.
	// Defaults to 5432.
	// +optional
	Port int32 `json:"port,omitempty"`

	// Credentials for the Postgres instance.
	// +optional
	Credentials *PostgresCredentials `json:"credentials,omitempty"`

	// GoogleCloudSQL may used to automatically provision a Google CloudSQL instance.
	// Do not specify a host, port or credentials if this is used.
	// +optional
	GoogleCloudSQL *GoogleCloudSQLPostgresStore `json:"googleCloudSql,omitempty"`
}

// PostgresCredentials is the credentials for a Postgres instance.
type PostgresCredentials struct {
	// Secret is the name of a secret in the local namespace to load the credentials from.
	// +optional
	Secret *corev1.LocalObjectReference `json:"secret,omitempty"`

	// UsernameKey is the key of the username in the secret. Defaults to username.
	// +optional
	UsernameKey string `json:"usernameKey,omitempty"`

	// PasswordKey is the key of the password in the secret. Defaults to password.
	// +optional
	PasswordKey string `json:"passwordKey,omitempty"`

	// DatabaseKey is the key of the database in the secret. Defaults to database.
	// +optional
	DatabaseKey string `json:"databaseKey,omitempty"`
}

// GoogleCloudSQLPostgresStore creates a managed Postgres instance for the StatefulService.
type GoogleCloudSQLPostgresStore struct {
	// The name of the Cloud SQL instance. If not specified, the name of the stateful store resource
	// will be used.
	// +optional
	Name string `json:"name,omitempty"`

	// The number of cores the Postgres instance should use.
	// Must be 1, or an even number between 2 and 64.
	// Defaults to 1.
	// Modifiable after creation but will take store offline for several minutes.
	// +kubebuilder:validation:Minimum=1
	// +kubebuilder:validation:Maximum=64
	// +optional
	Cores int32 `json:"cores,omitempty"`

	// The amount of memory the Postgres instance should have.
	// Must be between 0.9 GiB and 6.5 GiB per vCPU, and must be	// a multiple of 256 MiB, and at least 3.75 GiB.
	// Defaults to 3.75 GiB.
	// Modifiable after creation but will take store offline for several minutes.
	// +optional
	Memory resource.Quantity `json:"memory,omitempty"`

	// The storage configuration.
	// +optional
	Storage GoogleCloudSQLPostgresStorage `json:"storage,omitempty"`

	// Whether high availability is enabled.
	// High availability instances run a second slave postgres
	// instance that can be failed over to should the first fail.
	// High availability instances roughly double costs.
	// Defaults to false.
	// Modifiable after creation but will take store offline for several minutes.
	// +optional
	HighAvailability bool `json:"highAvailability,omitempty"`
}

type GoogleCloudSQLPostgresStorage struct {
	// The type of disks for the storage to use, either SSD or HDD.
	// Cannot be changed after creation. Defaults to SSD.
	// +optional
	Type GoogleCloudSQLPostgresStorageType `json:"type,omitempty"`

	// The capacity for the storage.
	// Can be any value up to 30,720 GB.
	// Defaults to 1GB. After creation, can only be increased.
	// +optional
	Capacity resource.Quantity `json:"capacity,omitempty"`

	// Whether the storage capacity may be automatically increased as needed.
	// Defaults to true.
	// Modifiable after creation.
	// +optional
	AutomaticIncrease bool `json:"automaticIncrease,omitempty"`

	// A limit to how high the capacity may be automatically increased.
	// A value of 0 means no limit. The default is 0.
	// Modifiable after creation.
	// +optional
	AutomaticIncreaseLimit resource.Quantity `json:"automaticIncreaseLimit,omitempty"`
}

// +kubebuilder:validation:Enum=SSD;HDD
type GoogleCloudSQLPostgresStorageType string

const (
	SSDStorage GoogleCloudSQLPostgresStorageType = "SSD"
	HDDStorage GoogleCloudSQLPostgresStorageType = "HDD"
)

// SpannerStore simply indicates the use of a Cloudstate-managed Spanner store.
type SpannerStore struct {
	// Project is the GCP project to use.
	Project string `json:"string"`

	// Instance is the Spanner instance id.
	Instance string `json:"instance"`

	// Database is the Spanner database id.
	// Defaults to cloudstate, and can be overridden on a per deployment basis.
	// +optional
	Database string `json:"database,omitempty"`

	// Secret the secret to read Spanner credentials from.
	// Must have a key.json file in it.
	// +optional
	Secret *corev1.LocalObjectReference `json:"secret,omitempty"`
}

// GcpCredentials are the credentials for a Cloudstate  is the credentials for a Spanner instance.
type GcpCredentials struct {
	// Secret is the name of a secret in the local namespace to load the credentials from.
	// +kubebuilder:validation:MinLength=1
	// +optional
	Secret *corev1.LocalObjectReference `json:"secret,omitempty"`

	// UsernameKey is the key of the username in the secret. Defaults to username.
	// +optional
	UsernameKey string `json:"usernameKey,omitempty"`

	// PasswordKey is the key of the password in the secret. Defaults to password.
	// +optional
	PasswordKey string `json:"passwordKey,omitempty"`

	// KeyspaceKey is the key of the keyspace in the secret. Defaults to keyspace.
	// +optional
	KeyspaceKey string `json:"keyspaceKey,omitempty"`
}

type CassandraStore struct {
	// Host to connect to.
	// +kubebuilder:validation:MinLength=1
	// +optional
	Host string `json:"host,omitempty"`

	// Port to connect to.
	// Defaults to 9042.
	// +optional
	Port int32 `json:"port,omitempty"`

	// Credentials is a reference to a secret in the same namespace to use for
	// the username and password of the Cassandra database.
	// +optional
	Credentials *CassandraCredentials `json:"credentials,omitempty"`
}

// CassandraCredentials is the credentials for a Cassandra instance.
type CassandraCredentials struct {
	// Secret is the name of a secret in the local namespace to load the credentials from.
	// +optional
	Secret *corev1.LocalObjectReference `json:"secret,omitempty"`

	// UsernameKey is the key of the username in the secret. Defaults to username.
	// +optional
	UsernameKey string `json:"usernameKey,omitempty"`

	// PasswordKey is the key of the password in the secret. Defaults to password.
	// +optional
	PasswordKey string `json:"passwordKey,omitempty"`

	// KeyspaceKey is the key of the keyspace in the secret. Defaults to keyspace.
	// +optional
	KeyspaceKey string `json:"keyspaceKey,omitempty"`
}

// StatefulStoreStatus defines the observed state of StatefulStore.
type StatefulStoreStatus struct {
	// Summary of the current status.
	// +optional
	Summary string `json:"summary,omitempty"`

	// +optional
	Conditions []CloudstateCondition `json:"conditions,omitempty"`

	// Postgres if this is a postgres store, the status of that store.
	// +optional
	Postgres *PostgresStoreStatus `json:"postgres,omitempty"`
}

// PostgresStoreStatus defines the state of a postgres store.
type PostgresStoreStatus struct {

	// GoogleCloudSQL is the status of the managed Google Cloud SQL Postgres instance.
	// +optional
	GoogleCloudSQL *GoogleCloudSQLPostgresStoreStatus `json:"googleCloudSql,omitempty"`
}

// GoogleCloudSQLPostgresStoreStatus defines the status of a Google Cloud SQL postgres store.
type GoogleCloudSQLPostgresStoreStatus struct {

	// InstanceName is the name of the instance.
	InstanceName string `json:"instanceName"`
}

// StatefulStore is the Schema for the statefulstores API.
// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:printcolumn:name="Age",type="date",JSONPath=".metadata.creationTimestamp"
// +kubebuilder:printcolumn:name="Status",type=string,JSONPath=`.status.summary`
type StatefulStore struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   StatefulStoreSpec   `json:"spec,omitempty"`
	Status StatefulStoreStatus `json:"status,omitempty"`
}

// StatefulStoreList contains a list of StatefulStore.
// +kubebuilder:object:root=true
type StatefulStoreList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []StatefulStore `json:"items"`
}

func init() {
	SchemeBuilder.Register(&StatefulStore{}, &StatefulStoreList{})
}
