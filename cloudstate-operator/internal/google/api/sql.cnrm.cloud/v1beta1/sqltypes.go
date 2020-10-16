package v1alpha3

// Since Google config-connector doesn't yet provide a Go API, we
// need to use Unstructured to work with it with controller-runtime.
// This file allows us to hide some of the ugly details.
//
// Note that an instance of one of these types in Go
// (sqlinstance/sqldatabase/sqluser) is only a spec for an actual
// instance of the corresponding type in GCP.
// (eg. sql.cnrm.cloud.google.com.SQLInstance)  The config connector
// CRDs and controller need to be installed on the cluster in order
// for the corresponding GCP instance to be created according to the spec.

import (
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime/schema"
)

// TODO: Change the name of this file.  It's not just about SQL stuff anymore

var (
	SQLInstanceGVK = schema.GroupVersionKind{
		Group:   "sql.cnrm.cloud.google.com",
		Kind:    "SQLInstance",
		Version: "v1beta1",
	}
	SQLDatabaseGVK = schema.GroupVersionKind{
		Group:   "sql.cnrm.cloud.google.com",
		Kind:    "SQLDatabase",
		Version: "v1beta1",
	}
	SQLUserGVK = schema.GroupVersionKind{
		Group:   "sql.cnrm.cloud.google.com",
		Kind:    "SQLUser",
		Version: "v1beta1",
	}

	SpannerInstanceGVK = schema.GroupVersionKind{
		Group:   "spanner.cnrm.cloud.google.com",
		Kind:    "SpannerInstance",
		Version: "v1beta1",
	}
	SpannerDatabaseGVK = schema.GroupVersionKind{
		Group:   "spanner.cnrm.cloud.google.com",
		Kind:    "SpannerDatabase",
		Version: "v1beta1",
	}

	IAMServiceAccountGVK = schema.GroupVersionKind{
		Group:   "iam.cnrm.cloud.google.com",
		Kind:    "IAMServiceAccount",
		Version: "v1beta1",
	}
	IAMPolicyMemberGVK = schema.GroupVersionKind{
		Group:   "iam.cnrm.cloud.google.com",
		Kind:    "IAMPolicyMember",
		Version: "v1beta1",
	}
	IAMServiceAccountKeyGVK = schema.GroupVersionKind{
		Group:   "iam.cnrm.cloud.google.com",
		Kind:    "IAMServiceAccountKey",
		Version: "v1beta1",
	}
)

type SQLInstance = unstructured.Unstructured
type SQLDatabase = unstructured.Unstructured
type SQLUser = unstructured.Unstructured
type SpannerInstance = unstructured.Unstructured
type SpannerDatabase = unstructured.Unstructured
type IAMServiceAccount = unstructured.Unstructured
type IAMPolicyMember = unstructured.Unstructured
type IAMServiceAccountKey = unstructured.Unstructured

func NewSQLInstance() *SQLInstance {
	i := new(SQLInstance)
	i.SetGroupVersionKind(SQLInstanceGVK)
	return i
}

// Creates a sqlinstance object initialized with the given content
func MakeSQLInstance(content map[string]interface{}) *SQLInstance {
	i := new(SQLInstance)
	i.SetUnstructuredContent(content)
	i.SetGroupVersionKind(SQLInstanceGVK)
	return i
}

func NewSQLDatabase() *SQLDatabase {
	d := new(SQLDatabase)
	d.SetGroupVersionKind(SQLDatabaseGVK)
	return d
}

// Creates a sqldatabase object initialized with the given content
func MakeSQLDatabase(content map[string]interface{}) *SQLDatabase {
	d := new(SQLDatabase)
	d.SetUnstructuredContent(content)
	d.SetGroupVersionKind(SQLDatabaseGVK)
	return d
}

func NewSQLUser() *SQLUser {
	u := new(SQLUser)
	u.SetGroupVersionKind(SQLUserGVK)
	return u
}

// Creates a sqluser object initialized with the given content
func MakeSQLUser(content map[string]interface{}) *SQLUser {
	u := new(SQLUser)
	u.SetUnstructuredContent(content)
	u.SetGroupVersionKind(SQLUserGVK)
	return u
}

func NewSpannerInstance() *SpannerInstance {
	i := new(SpannerInstance)
	i.SetGroupVersionKind(SpannerInstanceGVK)
	return i
}

// Creates a spannerinstance object initialized with the given content
func MakeSpannerInstance(content map[string]interface{}) *SpannerInstance {
	i := new(SpannerInstance)
	i.SetUnstructuredContent(content)
	i.SetGroupVersionKind(SpannerInstanceGVK)
	return i
}

func NewSpannerDatabase() *SpannerDatabase {
	i := new(SpannerDatabase)
	i.SetGroupVersionKind(SpannerDatabaseGVK)
	return i
}

// Creates a spannerdatabase object initialized with the given content
func MakeSpannerDatabase(content map[string]interface{}) *SpannerDatabase {
	i := new(SpannerDatabase)
	i.SetUnstructuredContent(content)
	i.SetGroupVersionKind(SpannerDatabaseGVK)
	return i
}

func NewIAMServiceAccount() *IAMServiceAccount {
	i := new(IAMServiceAccount)
	i.SetGroupVersionKind(IAMServiceAccountGVK)
	return i
}

// Creates an IAMServiceAccount object initialized with the given content
func MakeIAMServiceAccount(content map[string]interface{}) *IAMServiceAccount {
	i := new(IAMServiceAccount)
	i.SetUnstructuredContent(content)
	i.SetGroupVersionKind(IAMServiceAccountGVK)
	return i
}

func NewIAMPolicyMember() *IAMPolicyMember {
	i := new(IAMPolicyMember)
	i.SetGroupVersionKind(IAMPolicyMemberGVK)
	return i
}

// Creates an IAMPolicyMember object initialized with the given content
func MakeIAMPolicyMember(content map[string]interface{}) *IAMPolicyMember {
	i := new(IAMPolicyMember)
	i.SetUnstructuredContent(content)
	i.SetGroupVersionKind(IAMPolicyMemberGVK)
	return i
}

func NewIAMServiceAccountKey() *IAMServiceAccountKey {
	i := new(IAMServiceAccountKey)
	i.SetGroupVersionKind(IAMServiceAccountKeyGVK)
	return i
}

// Creates an IAMServiceAccountKey object initialized with the given content
func MakeIAMServiceAccountKey(content map[string]interface{}) *IAMServiceAccountKey {
	i := new(IAMServiceAccountKey)
	i.SetUnstructuredContent(content)
	i.SetGroupVersionKind(IAMServiceAccountKeyGVK)
	return i
}
