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

package config

import (
	"fmt"
	// v3 is needed because when we load the config over our defaults, v2 will write empty values
	// over the defaults when the value is not specified, whereas v3 will leave it alone.
	"gopkg.in/yaml.v3"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
)

// exampleStatefulServiceConfig is what goes into the ConfigMap by default.
// It is not the default configuration for a stateful service (though ideally we should
// maintain it so the values below match the defaults), rather, every config setting is
// commented out. This serves as an example of what can be configured so that when we
// want to override the defaults, we can edit the configmap using kubectl edit, this
// big comment will be in there, allowing us find the setting we want to override,
// uncomment it, and update its value.
// VERY IMPORTANT: There must be no trailing spaces on any lines below. When kubectl outputs
// the config map as YAML, it will only represent the string below as a block chomp if this
// is true, otherwise, it gets put into a string with newlines encoded as \n and so on.
const exampleStatefulServiceConfig = `
# Settings for the autoscaler
autoscaler:

  # Whether the autoscaler should be enabled or not
  # enabled: true

  # The minimum number of replicas to scale down to
  # minReplicas: 1

  # The maximum number of replicas to scale up to
  # maxReplicas: 10

  # The average CPU utilization threshold, at which point the autoscaler will
  # scale up or down, as a percentage of requested CPU
  # cpuUtilizationThreshold: 80

# Settings for the proxy
proxy:

  # The image, setting this will override the image selected by the operator for
  # the configured stateful store.
  # image: gcr.io/cloudstateengine/cloudstate-proxy-postgres-native:1.2.3

  # The image pull policy
  # imagePullPolicy: IfNotPresent

  # Proxy resource requirements
  resources:

    # The CPU request
    # cpuRequest: 400m

    # The CPU limit - is not set by default, and is generally a bad idea
    # cpuLimit:

    # The memory request
    # memoryRequest: 512Mi

    # The memory limit
    # memoryLimit: 512Mi

  # The max heap size for the proxy JVM
  # maxHeapSize: 256m

  # The initial heap size for the proxy JVM
  # initialHeapSize: 256m

# Settings for the user function
userFunction:

  # User function resource requirements
  resources:

    # The CPU request
    # cpuRequest: 400m

    # The CPU limit - is not set by default, and is generally a bad idea
    # cpuLimit:

    # The memory request
    # memoryRequest: 512Mi

    # The memory limit
    # memoryLimit: 512Mi
`

func NewStatefulServiceConfigWithDefaults() *StatefulServiceConfig {
	config := StatefulServiceConfig{}

	config.Autoscaler.Enabled = true
	config.Autoscaler.MinReplicas = 1
	config.Autoscaler.MaxReplicas = 10
	config.Autoscaler.CPUUtilizationThreshold = 80

	config.Proxy.ImagePullPolicy = corev1.PullIfNotPresent
	config.Proxy.MaxHeapSize = "256m"
	config.Proxy.InitialHeapSize = "256m"
	config.Proxy.Resources.CPURequest = "400m"
	config.Proxy.Resources.MemoryRequest = "512Mi"
	config.Proxy.Resources.MemoryLimit = "512Mi"

	config.UserFunction.Resources.CPURequest = "400m"
	config.UserFunction.Resources.MemoryRequest = "512Mi"
	config.UserFunction.Resources.MemoryLimit = "512Mi"

	return &config
}

// Initializes a ConfigMap with the example configuration comment.
func SetExampleStatefulServiceConfigMap(configMap *corev1.ConfigMap) {
	configMap.Data["config.yaml"] = exampleStatefulServiceConfig
}

// TODO REVIEW: this is a very long function name.
func ParseStatefulServiceFromConfigMapWithDefaults(configMap *corev1.ConfigMap) (*StatefulServiceConfig, error) {
	config := NewStatefulServiceConfigWithDefaults()
	if err := yaml.Unmarshal([]byte(configMap.Data["config.yaml"]), config); err != nil {
		return nil, err
	}
	return config, nil
}

// StatefulServiceConfig is defined per service in a ConfigMap.
type StatefulServiceConfig struct {
	Autoscaler   StatefulServiceAutoscalerConfig   `yaml:"autoscaler"`
	Proxy        StatefulServiceProxyConfig        `yaml:"proxy"`
	UserFunction StatefulServiceUserFunctionConfig `yaml:"userFunction"`
}

type StatefulServiceAutoscalerConfig struct {
	Enabled                 bool  `yaml:"enabled"`
	MinReplicas             int32 `yaml:"minReplicas"`
	MaxReplicas             int32 `yaml:"maxReplicas"`
	CPUUtilizationThreshold int32 `yaml:"cpuUtilizationThreshold"`
}

type StatefulServiceProxyConfig struct {
	// Pointer so that it can be left unset.
	Image           *string                       `yaml:"image"`
	ImagePullPolicy corev1.PullPolicy             `yaml:"imagePullPolicy"`
	Resources       StatefulServiceResourceConfig `yaml:"resources"`
	InitialHeapSize string                        `yaml:"initialHeapSize"`
	MaxHeapSize     string                        `yaml:"maxHeapSize"`
}

type StatefulServiceUserFunctionConfig struct {
	Resources StatefulServiceResourceConfig `yaml:"resources"`
}

type StatefulServiceResourceConfig struct {
	CPURequest string `yaml:"cpuRequest"`
	// Pointer so that it can be left unset.
	CPULimit      *string `yaml:"cpuLimit"`
	MemoryRequest string  `yaml:"memoryRequest"`
	MemoryLimit   string  `yaml:"memoryLimit"`
}

func (rc *StatefulServiceResourceConfig) ToResourceRequirements() (*corev1.ResourceRequirements, error) {
	cpuRequest, err := resource.ParseQuantity(rc.CPURequest)
	if err != nil {
		return nil, fmt.Errorf("error parsing CPU request %q: %w", rc.CPURequest, err)
	}
	memoryRequest, err := resource.ParseQuantity(rc.MemoryRequest)
	if err != nil {
		return nil, fmt.Errorf("error parsing memory request %q: %w", rc.MemoryRequest, err)
	}
	memoryLimit, err := resource.ParseQuantity(rc.MemoryLimit)
	if err != nil {
		return nil, fmt.Errorf("error parsing memory limit %q: %w", rc.MemoryLimit, err)
	}

	requirements := &corev1.ResourceRequirements{
		Requests: corev1.ResourceList{
			corev1.ResourceCPU:    cpuRequest,
			corev1.ResourceMemory: memoryRequest,
		},
		Limits: corev1.ResourceList{
			corev1.ResourceMemory: memoryLimit,
		},
	}
	if rc.CPULimit != nil {
		cpuLimit, err := resource.ParseQuantity(*rc.CPULimit)
		if err != nil {
			return nil, fmt.Errorf("error parsing CPU limit %q: %w", *rc.CPULimit, err)
		}
		requirements.Limits[corev1.ResourceCPU] = cpuLimit
	}
	return requirements, nil
}
