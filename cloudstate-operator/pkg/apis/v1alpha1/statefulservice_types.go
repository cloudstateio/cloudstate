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
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// StatefulServiceSpec defines the desired state of StatefulService.
type StatefulServiceSpec struct {
	// The containers to run in the StatefulService. At least one container is required.
	// +kubebuilder:validation:MinItems=1
	Containers []corev1.Container `json:"containers"`

	// The number of replicas to deploy for the StatefulService.
	// Defaults to 1.
	// +kubebuilder:validation:Minimum=0
	// +optional
	Replicas *int32 `json:"replicas,omitempty"`

	// The data store that Cloudstate will use for persisting state.
	// Defaults to using an in-memory store.
	// +optional
	StoreConfig *StatefulServiceStoreConfig `json:"storeConfig,omitempty"`
}

// StatefulServiceStoreConfig defines the StatefulStore to use and any configuration options.
type StatefulServiceStoreConfig struct {
	// A reference to the StatefulStore to use.
	StatefulStore corev1.LocalObjectReference `json:"statefulStore"`

	// The name of the database to use in the StatefulStore if it supports it.
	// This is used to namespace the StatefulService's data, so multiple services
	// can share the same StatefulStore.
	// Defaults to "cloudstate".
	// +kubebuilder:validation:MinLength=1
	// +optional
	Database string `json:"database,omitempty"`

	// The secret to use for the StatefulStore if it supports it.
	// +optional
	Secret *corev1.LocalObjectReference `json:"secret,omitempty"`
}

// StatefulServiceStatus defines the observed state of StatefulService.
type StatefulServiceStatus struct {
	// Summary of the current status.
	// +optional
	Summary string `json:"summary,omitempty"`

	// Replicas that are available for the StatefulService.
	Replicas int32 `json:"replicas"`

	// Selector that is used to match pods associated with this StatefulService.
	// This is needed for the scale sub resource to allow the Kubernetes HPA to work.
	Selector string `json:"selector"`

	// Conditions of the StatefulService.
	// +optional
	Conditions []CloudstateCondition `json:"conditions,omitempty"`
}

// CloudstateCondition defines the possible conditions of stateful services.
type CloudstateCondition struct {
	// Type of deployment condition.
	Type CloudstateConditionType `json:"type"`

	// Status of the condition, one of True, False, Unknown.
	Status corev1.ConditionStatus `json:"status"`

	// Last time the condition transitioned from one status to another.
	LastTransitionTime metav1.Time `json:"lastTransitionTime"`

	// The reason for the condition's last transition.
	Reason string `json:"reason"`

	// A human readable message indicating details about the transition.
	Message string `json:"message"`
}

// CloudstateConditionType are the types of conditions in a StatefulServiceStatus.
type CloudstateConditionType string

const (
	// NotReady indicates whether the StatefulService is not ready. This
	// can be for a variety of reasons. More detail is available in the Reason
	// field.
	CloudstateNotReady CloudstateConditionType = "NotReady"
)

// StatefulService is the Schema for the statefulservices API.
// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:subresource:scale:specpath=.spec.replicas,statuspath=.status.replicas,selectorpath=.status.selector
// +kubebuilder:printcolumn:name="Age",type="date",JSONPath=".metadata.creationTimestamp"
// +kubebuilder:printcolumn:name="Replicas",type=integer,JSONPath=`.status.replicas`
// +kubebuilder:printcolumn:name="Status",type=string,JSONPath=`.status.summary`
type StatefulService struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   StatefulServiceSpec   `json:"spec,omitempty"`
	Status StatefulServiceStatus `json:"status,omitempty"`
}

// StatefulServiceList contains a list of StatefulService.
// +kubebuilder:object:root=true
type StatefulServiceList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []StatefulService `json:"items"`
}

func init() {
	SchemeBuilder.Register(&StatefulService{}, &StatefulServiceList{})
}
