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

	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/stores"

	gcloud "github.com/cloudstateio/cloudstate/cloudstate-operator/internal/google/api/sql.cnrm.cloud/v1beta1"
	cloudstate "github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/apis/v1alpha1"
	"github.com/k0kubun/pp"
	. "github.com/onsi/ginkgo"
	. "github.com/onsi/gomega"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
)

func init() {
	pp.ColoringEnabled = false
}

var _ = Describe("StatefulStore Controller", func() {
	ctx := context.Background()
	var namespace string

	BeforeEach(func() {
		namespace = createTestNamespace(ctx)
	})

	AfterEach(func() {
		deleteNamespace(ctx, namespace)
	})

	Context("when an InMemory StatefulStore is created", func() {
		var (
			statefulStore *cloudstate.StatefulStore
		)

		BeforeEach(func() {
			statefulStore = &cloudstate.StatefulStore{
				ObjectMeta: metav1.ObjectMeta{
					Name:      "my-inmemory-statefulstore",
					Namespace: namespace,
				},
				Spec: cloudstate.StatefulStoreSpec{
					InMemory: true,
				},
			}

			Expect(k8sClient.Create(ctx, statefulStore)).To(Succeed(), "failed to create statefulstore")
		})

		It("should update its status", func() {
			actual := &cloudstate.StatefulStore{}
			waitForResource(ctx, namespace, statefulStore.Name, actual)

			By("InMemory store not ready yet")
			Expect(actual.Status.Conditions).To(HaveLen(0), "should not have a status condition")

			By("InMemory store ready")
			Eventually(func() bool {
				waitForResource(ctx, namespace, statefulStore.Name, actual)
				return len(actual.Status.Conditions) > 0
			}, waitTimeout, pollInterval).Should(BeTrue(), "InMemory store has no status conditions")
			condition := actual.Status.Conditions[0]
			Expect(condition.Type).To(Equal(cloudstate.CloudstateNotReady))
			Expect(condition.Status).To(Equal(corev1.ConditionFalse))
			Expect(actual.Status.Summary).To(Equal("Ready"))
		})
	})

	Context("when a default ManagedPostgresStore StatefulStore is created", func() {
		var (
			statefulStore *cloudstate.StatefulStore
			// secretName      string
		)

		BeforeEach(func() {
			statefulStore = &cloudstate.StatefulStore{
				ObjectMeta: metav1.ObjectMeta{
					Name:      "my-default-managed-statefulstore",
					Namespace: namespace,
				},
				Spec: cloudstate.StatefulStoreSpec{
					Postgres: &cloudstate.PostgresStore{
						GoogleCloudSQL: &cloudstate.GoogleCloudSQLPostgresStore{},
					},
				},
			}

			Expect(k8sClient.Create(ctx, statefulStore)).To(Succeed(), "failed to create statefulstore")
			// secretName = "postgres-cnx-" + statefulStore.Name
		})

		It("should create a SQL instance", func() {
			sqlInstance := gcloud.NewSQLInstance()
			waitForResource(ctx, namespace, "my-default-managed-statefulstore", sqlInstance)
			pp.Fprintf(GinkgoWriter, "Created SQLInstance: %v\n", *sqlInstance)

			By("owned by the stateful store")
			Expect(sqlInstance).To(beOwnedBy(statefulStore))
		})

		// Can't test this atm because we're not wired to GCP so no SQLInstance is ever created.
		// The secret depends on info from that so will never come into existence at this time.
		// It("should create an instance cnx info secret", func() {
		//	secret := &corev1.Secret{}
		//	waitForResource(ctx, namespace, secretName, secret)
		//	pp.Fprintf(GinkgoWriter, "Created secret for SQL instance cnx: %v\n", *secret)
		//
		//	By("owned by the stateful store")
		//	Expect(secret).To(beOwnedBy(statefulStore))
		// })

		It("should update its status", func() {
			sqlInstance := gcloud.NewSQLInstance()
			waitForResource(ctx, namespace, "my-default-managed-statefulstore", sqlInstance)

			actual := &cloudstate.StatefulStore{}
			waitForResource(ctx, namespace, statefulStore.Name, actual)

			By("SQLInstance not ready yet")
			Expect(actual.Status.Conditions).To(HaveLen(1), "should have a status condition")
			condition := actual.Status.Conditions[0]
			Expect(condition.Type).To(Equal(stores.PostgresGoogleCloudSQLInstanceNotReady))
			Expect(condition.Status).To(Equal(corev1.ConditionTrue))
			Expect(actual.Status.Summary).To(Equal("CloudSqlInstanceUnknownStatus"))

			// TODO: In order for the status to progress further we'll need the test
			// environment actually wired to GCP or have mocks in place.
		})

		Context("when the StatefulStore is updated", func() {
			BeforeEach(func() {
				sqlInstance := gcloud.NewSQLInstance()
				waitForResource(ctx, namespace, "my-default-managed-statefulstore", sqlInstance)

				actual := &cloudstate.StatefulStore{}
				Eventually(func() error {
					waitForResource(ctx, namespace, statefulStore.Name, actual)
					actual.Spec.Postgres.GoogleCloudSQL.Cores = 4
					return k8sClient.Update(ctx, actual)
				}, waitTimeout, pollInterval).Should(Succeed(), "failed to update stateful store")
				waitForResource(ctx, namespace, statefulStore.Name, actual)
				cores := actual.Spec.Postgres.GoogleCloudSQL.Cores
				Expect(cores).To(Equal(int32(4)))
			})

			It("should update the sqlinstance", func() {
				sqlInstance := gcloud.NewSQLInstance()
				Eventually(func() string {
					waitForResource(ctx, namespace, "my-default-managed-statefulstore", sqlInstance)
					tier, _, _ := unstructured.NestedString(sqlInstance.Object, "spec", "settings", "tier")
					return tier
				}, waitTimeout, pollInterval).Should(Equal("db-custom-4-3840"))
			})
		})
	})

	Context("when a customized ManagedPostgresStore StatefulStore is created", func() {
		var (
			statefulStore *cloudstate.StatefulStore
		)

		BeforeEach(func() {
			memory, _ := resource.ParseQuantity("3.75Gi")
			capacity, _ := resource.ParseQuantity("2Gi")
			// automaticIncreaseLimit, _ := resource.ParseQuantity("10Gi")
			statefulStore = &cloudstate.StatefulStore{
				ObjectMeta: metav1.ObjectMeta{
					Name:      "my-custom-postgres-statefulstore",
					Namespace: namespace,
				},
				Spec: cloudstate.StatefulStoreSpec{
					Postgres: &cloudstate.PostgresStore{
						GoogleCloudSQL: &cloudstate.GoogleCloudSQLPostgresStore{
							Cores:            4,
							Memory:           memory,
							HighAvailability: true,
							Storage: cloudstate.GoogleCloudSQLPostgresStorage{
								Type:              "SSD",
								Capacity:          capacity,
								AutomaticIncrease: true,
								// TODO: not implemented.  What to do?
								// AutomaticIncreaseLimit: automaticIncreaseLimit,
							},
						},
					},
				},
			}

			Expect(k8sClient.Create(ctx, statefulStore)).To(Succeed(), "failed to create statefulstore")
		})

		It("should create a customized SQL instance", func() {
			sqlInstance := gcloud.NewSQLInstance()
			waitForResource(ctx, namespace, "my-custom-postgres-statefulstore", sqlInstance)
			pp.Fprintf(GinkgoWriter, "Created SQLInstance: %v\n", *sqlInstance)

			By("with requested customizations")
			sqlIContent := sqlInstance.UnstructuredContent()
			sqlSpec := sqlIContent["spec"].(map[string]interface{})
			settings := sqlSpec["settings"].(map[string]interface{})
			Expect(settings["availabilityType"]).To(Equal("REGIONAL"))
			Expect(settings["diskAutoresize"]).To(BeTrue())
			Expect(settings["diskSize"]).To(Equal(int64(2147483648))) // Better way to represent this?
			Expect(settings["diskType"]).To(Equal("SSD"))
			Expect(settings["tier"]).To(Equal("db-custom-4-3840"))

			// Will have to tweak StatefulStoreReconciler in suite-test.go somehow to test this...
			// region := sqlSpec["region"].(string)

			By("owned by the stateful store")
			Expect(sqlInstance).To(beOwnedBy(statefulStore))
		})
	})
})
