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

// TODO: describe package here.
package webhooks

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"

	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/config"
	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/controllers"
	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/stores"
	"github.com/go-logr/logr"
	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/util/intstr"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/webhook/admission"
)

const (
	KnativeServiceLabel = "serving.knative.dev/service"

	// UserPortName is the name of the user port. The pod injector will rewrite this port, and update the PORT
	// environment variable to the new value. Intentionally chosen to match Knatives user port name.
	UserPortName = "user-port"

	// DefaultUserPortRewrite is the default value that the pod injector will rewrite the user port to be. If this port
	// is already taken, it will increment by 1 until it finds one that isn't specified as a container port.
	DefaultUserPortRewrite = int32(8080)

	// DefaultCloudstatePort is the default port used if no user port can be determined.
	DefaultCloudstatePort = int32(8013)

	PortEnvVar = "PORT"

	CloudstateSidecarName = "cloudstate-sidecar"
)

type PodInjector struct {
	client  client.Client
	log     logr.Logger
	decoder *admission.Decoder
	store   stores.Stores
	config  *config.OperatorConfig
}

func NewPodInjector(client client.Client, log logr.Logger, store stores.Stores, config *config.OperatorConfig) *PodInjector {
	return &PodInjector{
		client: client,
		log:    log,
		store:  store,
		config: config,
	}
}

// This marker seems to be ignored, so I wrote the manifest manually.

// +kubebuilder:rbac:groups=apps,resources=deployments;replicasets,verbs=get;list;watch

// +kubebuilder:webhook:path=/inject-v1-pod,mutating=true,failurePolicy=fail,groups="",resources=pods,verbs=create;update,versions=v1,name=pod-injector.cloudstate.io
func (i *PodInjector) Handle(ctx context.Context, req admission.Request) admission.Response {
	var pod corev1.Pod
	if err := i.decoder.Decode(req, &pod); err != nil {
		return admission.Errored(http.StatusBadRequest, err)
	}
	log := i.log.WithValues("pod", req.Name, "namespace", req.Namespace)

	// First check if this has the Cloudstate enabled label.
	if pod.Annotations[controllers.CloudstateEnabledAnnotation] != "true" {
		return admission.Allowed("not a stateful service, not injecting")
	}

	// Check whether it's already injected.
	for _, container := range pod.Spec.Containers {
		if container.Name == CloudstateSidecarName {
			return admission.Allowed("already injected")
		}
	}

	if err := i.injectSidecar(ctx, log, req.Namespace, &pod); err != nil {
		log.Error(err, "Error injecting sidecar into pod")
		return admission.Errored(http.StatusInternalServerError, err)
	}

	marshaledPod, err := json.Marshal(pod)
	if err != nil {
		log.Error(err, "Error marshalling pod")
		return admission.Errored(http.StatusInternalServerError, err)
	}
	return admission.PatchResponseFromRaw(req.Object.Raw, marshaledPod)
}

func (i *PodInjector) loadConfig(ctx context.Context, namespace string, pod *corev1.Pod) (*config.StatefulServiceConfig, error) {
	if pod.Annotations[controllers.CloudstateStatefulServiceConfigAnnotation] != "" {
		configMap := &corev1.ConfigMap{}
		if err := i.client.Get(ctx, client.ObjectKey{
			Namespace: namespace,
			Name:      pod.Annotations[controllers.CloudstateStatefulServiceConfigAnnotation],
		}, configMap); err != nil {
			return nil, fmt.Errorf("error loading config map %s for stateful service as referenced by %s annotation: %w",
				pod.Annotations[controllers.CloudstateStatefulServiceConfigAnnotation], controllers.CloudstateStatefulServiceConfigAnnotation, err)
		}
		return config.ParseStatefulServiceFromConfigMapWithDefaults(configMap)
	}
	return config.NewStatefulServiceConfigWithDefaults(), nil
}

// Determine the selector to use for the pod.
func (i *PodInjector) determineSelector(ctx context.Context, log logr.Logger, namespace string, pod *corev1.Pod) (string, string, error) {
	// First, check if it has a stateful service label, if it does, use that
	if label := pod.Labels[controllers.CloudstateStatefulServiceLabel]; label != "" {
		return controllers.CloudstateStatefulServiceLabel, label, nil
	}
	// Support for Knative services - if there's a Knative service label, use that
	if label := pod.Labels[KnativeServiceLabel]; label != "" {
		return KnativeServiceLabel, label, nil
	}

	// Otherwise, fall back to walking up the tree to the owning deployment to determine the label selector
	var replicaSet *appsv1.ReplicaSet
	for _, owner := range pod.OwnerReferences {
		if strings.HasPrefix(owner.APIVersion, "apps/") && owner.Kind == "ReplicaSet" {
			replicaSet = &appsv1.ReplicaSet{}
			log.Info(fmt.Sprintf("Attempting to load replicaset %q:%q", pod.Namespace, owner.Name))
			if err := i.client.Get(ctx, client.ObjectKey{
				Namespace: namespace,
				Name:      owner.Name,
			}, replicaSet); err != nil {
				return "", "", fmt.Errorf("unable to load owning ReplicaSet of pod: %w", err)
			}
			log.Info("Located owning ReplicaSet", "Name", owner.Name)
			break
		}
	}
	var deployment *appsv1.Deployment
	if replicaSet != nil {
		for _, owner := range replicaSet.OwnerReferences {
			if strings.HasPrefix(owner.APIVersion, "apps/") && owner.Kind == "Deployment" {
				deployment = &appsv1.Deployment{}
				if err := i.client.Get(ctx, client.ObjectKey{
					Namespace: namespace,
					Name:      owner.Name,
				}, deployment); err != nil {
					return "", "", fmt.Errorf("unable to load owning Deployment of pod: %w", err)
				}
				log.Info("Located owning Deployment", "Name", owner.Name)
				break
			}
		}
	}

	// First we check if it's a deployment with a simple, single label match, and no match expressions
	if deployment != nil && deployment.Spec.Selector.MatchExpressions == nil && len(deployment.Spec.Selector.MatchLabels) == 1 {
		for label, value := range deployment.Spec.Selector.MatchLabels {
			log.Info("Located single match label selector", "Label", label, "Value", value)
			return label, value, nil
		}
	}

	// No simple selector found, fail
	return "", "", fmt.Errorf("cloudstate injected pods must either define a %s label, or a single match label selector", controllers.CloudstateStatefulServiceLabel)
}

func (i *PodInjector) determineAndRewritePorts(pod *corev1.Pod) (int32, int32) {
	// See if there's a user port declared
	var userContainer *corev1.Container
	var userPort *corev1.ContainerPort

	for idx := range pod.Spec.Containers {
		container := &pod.Spec.Containers[idx]
		for portIdx := range container.Ports {
			port := &container.Ports[portIdx]
			if port.Name == UserPortName {
				userContainer = container
				userPort = port
				break
			}
		}
	}

	// Did we find a container? If not, choose the first declared port that we can find.
	if userContainer == nil {
		for idx := range pod.Spec.Containers {
			container := &pod.Spec.Containers[idx]
			for portIdx := range container.Ports {
				userContainer = container
				userPort = &container.Ports[portIdx]
				break
			}
		}
	}

	// Still no container? Just take the first container, and add a port to it as the default
	if userContainer == nil {
		userContainer = &pod.Spec.Containers[0]
		userContainer.Ports = []corev1.ContainerPort{
			{
				Name:          UserPortName,
				ContainerPort: DefaultCloudstatePort,
			},
		}
		userPort = &userContainer.Ports[0]
	}

	// Decide on a port to use
	rewritePort := DefaultUserPortRewrite
	for isContainerPortInUse(pod, rewritePort) {
		rewritePort++
	}

	// Rewrite the port and inject it into the PORT environment variable.
	proxyPort := userPort.ContainerPort
	userPort.ContainerPort = rewritePort

	var portEnvVar *corev1.EnvVar
	for idx := range userContainer.Env {
		if userContainer.Env[idx].Name == PortEnvVar {
			portEnvVar = &userContainer.Env[idx]
			break
		}
	}
	if portEnvVar != nil {
		portEnvVar.ValueFrom = nil
		portEnvVar.Value = strconv.Itoa(int(rewritePort))
	} else {
		userContainer.Env = append(userContainer.Env, corev1.EnvVar{
			Name:  PortEnvVar,
			Value: strconv.Itoa(int(rewritePort)),
		})
	}

	return proxyPort, rewritePort
}

func isContainerPortInUse(pod *corev1.Pod, portToCheck int32) bool {
	for _, container := range pod.Spec.Containers {
		for _, port := range container.Ports {
			if port.ContainerPort == portToCheck {
				return true
			}
		}
	}
	return false
}

func (i *PodInjector) injectSidecar(ctx context.Context, log logr.Logger, namespace string, pod *corev1.Pod) error {
	// If the sidecar is already added, do nothing.
	for _, container := range pod.Spec.Containers {
		if container.Name == CloudstateSidecarName {
			return nil
		}
	}

	// Load the config for it, if it exists.
	config, err := i.loadConfig(ctx, namespace, pod)
	if err != nil {
		return err
	}

	pod.Annotations["traffic.sidecar.istio.io/excludeOutboundPorts"] = "2552,8558"
	pod.Annotations["traffic.sidecar.istio.io/excludeInboundPorts"] = "2552,8558"

	sidecarPort, userFunctionPort := i.determineAndRewritePorts(pod)

	readinessProbe := &corev1.Probe{
		Handler: corev1.Handler{
			HTTPGet: &corev1.HTTPGetAction{
				Path:   "/ready",
				Port:   intstr.FromInt(8558),
				Scheme: corev1.URISchemeHTTP,
			},
		},
		TimeoutSeconds:   1,
		PeriodSeconds:    10,
		SuccessThreshold: 1,
		FailureThreshold: 5,
	}
	livenessProbe := readinessProbe.DeepCopy()
	livenessProbe.Handler.HTTPGet.Path = "/alive"

	proxyResources, err := config.Proxy.Resources.ToResourceRequirements()
	if err != nil {
		return fmt.Errorf("error creating proxy resource requirements: %w", err)
	}
	selectorLabel, selectorLabelValue, err := i.determineSelector(ctx, log, namespace, pod)
	if err != nil {
		return err
	}
	pod.Spec.Containers = append(pod.Spec.Containers, corev1.Container{
		Name:            CloudstateSidecarName,
		ImagePullPolicy: config.Proxy.ImagePullPolicy,
		Ports: []corev1.ContainerPort{
			{
				ContainerPort: sidecarPort,
				Name:          "grpc-http-proxy",
				Protocol:      corev1.ProtocolTCP,
			},
			{
				ContainerPort: i.config.SidecarMetrics.Port,
				Name:          "cs-metrics",
				Protocol:      corev1.ProtocolTCP,
			},
		},
		ReadinessProbe: readinessProbe,
		LivenessProbe:  livenessProbe,
		Resources:      *proxyResources,
		Env: []corev1.EnvVar{
			{
				Name:  "USER_FUNCTION_PORT",
				Value: strconv.Itoa(int(userFunctionPort)),
			},
			{
				Name:  "METRICS_PORT",
				Value: strconv.Itoa(int(i.config.SidecarMetrics.Port)),
			},
			{
				Name:  "REMOTING_PORT",
				Value: "2552",
			},
			{
				Name:  "MANAGEMENT_PORT",
				Value: "8558",
			},
			{
				Name:  "HTTP_PORT",
				Value: strconv.Itoa(int(sidecarPort)),
			},
			{
				Name:  "SELECTOR_LABEL",
				Value: selectorLabel,
			},
			{
				Name:  "SELECTOR_LABEL_VALUE",
				Value: selectorLabelValue,
			},
			{
				Name:  "REQUIRED_CONTACT_POINT_NR",
				Value: "1",
			},
		},
	})

	// Now get the container we just added, it's the last one
	container := &pod.Spec.Containers[len(pod.Spec.Containers)-1]
	if err := i.store.InjectPodStoreConfig(ctx, selectorLabelValue, namespace, pod, container); err != nil {
		return err
	}

	// If the config has a proxy image override in it, set it
	if config.Proxy.Image != nil {
		container.Image = *config.Proxy.Image
	}

	return nil
}

func (i *PodInjector) InjectDecoder(d *admission.Decoder) error {
	i.decoder = d
	return nil
}
