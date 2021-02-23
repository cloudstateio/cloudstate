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

package controllers

import (
	"context"
	"fmt"

	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/stores"

	gcloud "github.com/cloudstateio/cloudstate/cloudstate-operator/internal/google/api/sql.cnrm.cloud/v1beta1"
	cloudstate "github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/apis/v1alpha1"
	"github.com/k0kubun/pp"
	. "github.com/onsi/ginkgo"
	. "github.com/onsi/gomega"
	"github.com/onsi/gomega/types"
	appsv1 "k8s.io/api/apps/v1"
	autoscalingv2beta2 "k8s.io/api/autoscaling/v2beta2"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
)

func init() {
	pp.ColoringEnabled = false
}

var _ = Describe("StatefulService Controller", func() {
	ctx := context.Background()
	var namespace string

	BeforeEach(func() {
		namespace = createTestNamespace(ctx)
	})

	AfterEach(func() {
		deleteNamespace(ctx, namespace)
	})

	Context("when a StatefulService with no store is created", func() {
		var (
			statefulService *cloudstate.StatefulService
		)

		BeforeEach(func() {
			var replicas int32 = 2
			statefulService = &cloudstate.StatefulService{
				ObjectMeta: metav1.ObjectMeta{
					Name:      "my-statefulservice",
					Namespace: namespace,
				},
				Spec: cloudstate.StatefulServiceSpec{
					Containers: []corev1.Container{{
						Image: "gcr.io/cloudstate/example-function:v0.0.1",
					}},
					Replicas:    &replicas,
					StoreConfig: nil,
				},
			}

			Expect(k8sClient.Create(ctx, statefulService)).To(Succeed(), "failed to create statefulservice")
		})

		It("should create a deployment", func() {
			deployment := &appsv1.Deployment{}
			waitForResource(ctx, namespace, statefulService.Name, deployment)
			pp.Fprintf(GinkgoWriter, "Created deployment: %v\n", *deployment)

			By("owned by the stateful service")
			Expect(deployment).To(beOwnedBy(statefulService))

			By("runs the requested user function")
			containers := deployment.Spec.Template.Spec.Containers
			var userContainer *corev1.Container
			for _, container := range containers {
				if container.Image == statefulService.Spec.Containers[0].Image {
					userContainer = &container
					break
				}
			}
			Expect(userContainer).ToNot(BeNil(), "missing user container in: %v", containers)

			By("with the requested number of replicas")
			Expect(deployment.Spec.Replicas).ToNot(BeNil())
			Expect(*deployment.Spec.Replicas).To(Equal(*statefulService.Spec.Replicas))
		})

		It("should update its status", func() {
			deployment := &appsv1.Deployment{}
			waitForResource(ctx, namespace, statefulService.Name, deployment)

			actual := &cloudstate.StatefulService{}
			waitForResource(ctx, namespace, statefulService.Name, actual)

			By("deployment not ready yet")
			Expect(actual.Status.Conditions).To(HaveLen(1), "should have a status condition")
			condition := actual.Status.Conditions[0]
			Expect(condition.Type).To(Equal(cloudstate.CloudstateNotReady))
			Expect(condition.Status).To(Equal(corev1.ConditionTrue))
			Expect(actual.Status.Selector).To(Equal(CloudstateStatefulServiceLabel + "=" + statefulService.Name))

			By("deployment is ready")
			replicas := *statefulService.Spec.Replicas
			deployment.Status.ObservedGeneration = deployment.Generation
			deployment.Status.Replicas = replicas
			deployment.Status.UpdatedReplicas = replicas
			deployment.Status.ReadyReplicas = replicas
			deployment.Status.AvailableReplicas = replicas
			Expect(k8sClient.Status().Update(ctx, deployment)).To(Succeed())
			Eventually(func() corev1.ConditionStatus {
				waitForResource(ctx, namespace, statefulService.Name, actual)
				return actual.Status.Conditions[0].Status
			}, waitTimeout, pollInterval).Should(Equal(corev1.ConditionFalse))
			Expect(actual.Status.Summary).To(Equal("Ready"))
			Expect(actual.Status.Replicas).To(Equal(int32(2)))

			By("no replicas available")
			deployment.Status.AvailableReplicas = 0
			Expect(k8sClient.Status().Update(ctx, deployment)).To(Succeed())
			Eventually(func() int32 {
				waitForResource(ctx, namespace, statefulService.Name, actual)
				return actual.Status.Replicas
			}, waitTimeout, pollInterval).Should(Equal(int32(0)))
			fmt.Fprintf(GinkgoWriter, "%v\n", actual.Status)
			Expect(actual.Status.Conditions[0].Status).To(Equal(corev1.ConditionTrue))
			Expect(actual.Status.Summary).To(Equal("Unavailable"))
			Expect(actual.Status.Replicas).To(Equal(int32(0)))

			By("some replicas available")
			deployment.Status.AvailableReplicas = 1
			Expect(k8sClient.Status().Update(ctx, deployment)).To(Succeed())
			Eventually(func() string {
				waitForResource(ctx, namespace, statefulService.Name, actual)
				return actual.Status.Conditions[0].Reason
			}, waitTimeout, pollInterval).Should(Equal("PartiallyReady"))
			Expect(actual.Status.Conditions[0].Status).To(Equal(corev1.ConditionTrue))
			Expect(actual.Status.Summary).To(Equal("PartiallyReady"))
			Expect(actual.Status.Replicas).To(Equal(int32(1)))

			By("deployment is in progress")
			deployment.Status.UpdatedReplicas = 0
			Expect(k8sClient.Status().Update(ctx, deployment)).To(Succeed())
			Eventually(func() string {
				waitForResource(ctx, namespace, statefulService.Name, actual)
				return actual.Status.Conditions[0].Reason
			}, waitTimeout, pollInterval).Should(Equal("UpdateInProgress"))
			Expect(actual.Status.Conditions[0].Status).To(Equal(corev1.ConditionTrue))
			Expect(actual.Status.Summary).To(Equal("UpdateInProgress"))
		})

		It("should create a HPA configuration", func() {
			hpa := &autoscalingv2beta2.HorizontalPodAutoscaler{}
			waitForResource(ctx, namespace, statefulService.Name, hpa)
			Expect(*hpa.Spec.MinReplicas).To(Equal(int32(1)))
			Expect(hpa.Spec.MaxReplicas).To(Equal(int32(10)))
			Expect(hpa.Spec.ScaleTargetRef.Kind).To(Equal("StatefulService"))
			Expect(hpa.Spec.ScaleTargetRef.APIVersion).To(Equal(cloudstate.GroupVersion.Identifier()))
			Expect(hpa.Spec.ScaleTargetRef.Name).To(Equal(statefulService.Name))
		})

		Context("when the StatefulService is updated", func() {
			BeforeEach(func() {
				deployment := &appsv1.Deployment{}
				waitForResource(ctx, namespace, statefulService.Name, deployment)

				actual := &cloudstate.StatefulService{}
				Eventually(func() error {
					waitForResource(ctx, namespace, statefulService.Name, actual)
					actual.Spec.Containers[0].Image = "gcr.io/cloudstate/example-function:v0.0.2"
					return k8sClient.Update(ctx, actual)
				}, waitTimeout, pollInterval).Should(Succeed(), "failed to update stateful service")

			})

			It("should update the deployment", func() {
				deployment := &appsv1.Deployment{}
				Eventually(func() string {
					waitForResource(ctx, namespace, statefulService.Name, deployment)
					containers := deployment.Spec.Template.Spec.Containers
					return containers[0].Image
				}, waitTimeout, pollInterval).Should(Equal("gcr.io/cloudstate/example-function:v0.0.2"))
			})
		})

		Context("when the stateful service config map is updated", func() {
			It("should update HPA config", func() {
				configMap := &corev1.ConfigMap{}
				waitForResource(ctx, namespace, "ss-cfg-"+statefulService.Name, configMap)

				configMap.Data["config.yaml"] = `
autoscaler:
  minReplicas: 5
`
				Expect(k8sClient.Update(ctx, configMap)).To(Succeed())
				Eventually(func() int32 {
					hpa := &autoscalingv2beta2.HorizontalPodAutoscaler{}
					waitForResource(ctx, namespace, statefulService.Name, hpa)
					return *hpa.Spec.MinReplicas
				}, waitTimeout, pollInterval).Should(Equal(int32(5)))

			})
		})
	})

	Context("when a StatefulService with StoreConfig is created", func() {
		const (
			databaseName      string = "my-database"
			statefulStoreName string = "my-default-managed-statefulstore"
		)
		var (
			statefulService *cloudstate.StatefulService
			statefulStore   *cloudstate.StatefulStore
			secretName      string
		)

		BeforeEach(func() {
			// Need to create a StatefulStore to use
			statefulStore = &cloudstate.StatefulStore{
				ObjectMeta: metav1.ObjectMeta{
					Name:      statefulStoreName,
					Namespace: namespace,
				},
				Spec: cloudstate.StatefulStoreSpec{
					Postgres: &cloudstate.PostgresStore{
						GoogleCloudSQL: &cloudstate.GoogleCloudSQLPostgresStore{},
					},
				},
			}
			Expect(k8sClient.Create(ctx, statefulStore)).To(Succeed(), "failed to create statefulstore")

			var replicas int32 = 2
			statefulService = &cloudstate.StatefulService{
				ObjectMeta: metav1.ObjectMeta{
					Name:      "my-statefulservice",
					Namespace: namespace,
				},
				Spec: cloudstate.StatefulServiceSpec{
					Containers: []corev1.Container{{
						Image: "gcr.io/cloudstate/example-function:v0.0.1",
					}},
					Replicas: &replicas,
					StoreConfig: &cloudstate.StatefulServiceStoreConfig{
						StatefulStore: corev1.LocalObjectReference{
							Name: statefulStoreName,
						},
						Database: databaseName,
					},
				},
			}

			Expect(k8sClient.Create(ctx, statefulService)).To(Succeed(), "failed to create statefulservice")
			secretName = "postgres-creds-" + statefulService.Name
		})

		It("should create a SQL database", func() {
			sqlDatabase := gcloud.NewSQLDatabase()
			waitForResource(ctx, namespace, databaseName, sqlDatabase)
			pp.Fprintf(GinkgoWriter, "Created SQLDatabase: %v\n", *sqlDatabase)

			By("owned by the stateful service")
			Expect(sqlDatabase).To(beOwnedBy(statefulService))
		})

		It("should create a SQL user credentials secret", func() {
			secret := &corev1.Secret{}
			waitForResource(ctx, namespace, secretName, secret)
			pp.Fprintf(GinkgoWriter, "Created secret for SQL user: %v\n", *secret)

			By("owned by the stateful service")
			Expect(secret).To(beOwnedBy(statefulService))
		})

		It("should create a SQL user", func() {
			sqlUser := gcloud.NewSQLUser()
			waitForResource(ctx, namespace, statefulService.Name, sqlUser)
			pp.Fprintf(GinkgoWriter, "Created SQL user: %v\n", *sqlUser)

			By("owned by the stateful service")
			Expect(sqlUser).To(beOwnedBy(statefulService))
			By("with password from created secret")
			actualSecretName, _, _ := unstructured.NestedString(sqlUser.Object, "spec", "password", "valueFrom", "secretKeyRef", "name")
			Expect(actualSecretName).To(Equal(secretName))
		})

		It("should update its status", func() {
			sqlDatabase := gcloud.NewSQLDatabase()
			waitForResource(ctx, namespace, databaseName, sqlDatabase)

			actual := &cloudstate.StatefulService{}
			waitForResource(ctx, namespace, statefulService.Name, actual)

			By("SQLDatabase not ready yet")
			Expect(actual.Status.Conditions).To(Or(HaveLen(3), HaveLen(4)), "should have 3 or 4 status conditions")
			cs := len(actual.Status.Conditions)
			databaseCondition := actual.Status.Conditions[cs-3]
			Expect(databaseCondition.Type).To(Equal(stores.PostgresGoogleCloudSQLDatabaseNotReady))
			Expect(databaseCondition.Status).To(Equal(corev1.ConditionTrue))
			userCondition := actual.Status.Conditions[cs-2]
			Expect(userCondition.Type).To(Equal(stores.PostgresGoogleCloudSQLUserNotReady))
			Expect(userCondition.Status).To(Equal(corev1.ConditionTrue))
			notReadyCondition := actual.Status.Conditions[cs-1]
			Expect(notReadyCondition.Type).To(Equal(cloudstate.CloudstateNotReady))
			Expect(notReadyCondition.Status).To(Equal(corev1.ConditionTrue))

			Expect(actual.Status.Summary).To(Or(Equal("CloudSqlDatabaseUnknownStatus"), Equal("CloudSqlInstanceNotCreated")))

			// TODO: In order for the status to progress further we'll need the test
			// environment actually wired to GCP or have mocks in place.
		})

	})
})

func beOwnedBy(owner interface{}) types.GomegaMatcher {
	ownerRefs := func(obj metav1.Object) []metav1.OwnerReference { return obj.GetOwnerReferences() }
	ownerName := func(obj metav1.Object) string { return ownerRefs(obj)[0].Name }
	ownerKind := func(obj metav1.Object) string { return ownerRefs(obj)[0].Kind }

	var actualOwnerKind string
	var actualOwnerName string
	switch t := owner.(type) {
	case *cloudstate.StatefulService:
		actualOwnerKind = "StatefulService"
		actualOwnerName = t.Name
	case *cloudstate.StatefulStore:
		actualOwnerKind = "StatefulStore"
		actualOwnerName = t.Name
	default:
		// Don't know what to put here...
	}

	return SatisfyAll(
		WithTransform(ownerRefs, HaveLen(1)),
		WithTransform(ownerName, Equal(actualOwnerName)),
		WithTransform(ownerKind, Equal(actualOwnerKind)),
	)
}
