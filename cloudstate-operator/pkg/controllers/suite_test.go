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
	"path/filepath"
	"testing"
	"time"

	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/config"
	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/stores"
	istio "istio.io/client-go/pkg/apis/networking/v1alpha3"
	"sigs.k8s.io/controller-runtime/pkg/envtest/printer"

	. "github.com/onsi/ginkgo"
	. "github.com/onsi/gomega"

	cloudstatev1alpha1 "github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/apis/v1alpha1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/util/rand"
	"k8s.io/client-go/kubernetes/scheme"
	"k8s.io/client-go/rest"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/envtest"
	logf "sigs.k8s.io/controller-runtime/pkg/log"
	"sigs.k8s.io/controller-runtime/pkg/log/zap"
	// +kubebuilder:scaffold:imports
)

// These tests use Ginkgo (BDD-style Go testing framework). Refer to
// http://onsi.github.io/ginkgo/ to learn more about Ginkgo.

var cfg *rest.Config
var k8sClient client.Client
var testEnv *envtest.Environment
var controllerStopCh chan struct{}

const (
	// timeout period and poll interval for waitForResource() and other Eventually() calls
	waitTimeout  time.Duration = 5 * time.Second
	pollInterval time.Duration = 100 * time.Millisecond
)

func TestAPIs(t *testing.T) {
	RegisterFailHandler(Fail)

	RunSpecsWithDefaultAndCustomReporters(t,
		"Controller Suite",
		[]Reporter{printer.NewlineReporter{}})
}

var _ = BeforeSuite(func(done Done) {
	logf.SetLogger(zap.New(zap.UseDevMode(true), zap.WriteTo(GinkgoWriter)))

	By("bootstrapping test environment")
	testEnv = &envtest.Environment{
		CRDDirectoryPaths: []string{
			filepath.Join("..", "..", "config", "crd", "bases"),
			filepath.Join(".", "test"),
			filepath.Join("..", "..", "deploy", "k8s", "execution", "base", "google"),
		},
	}

	var err error
	cfg, err = testEnv.Start()
	Expect(err).ToNot(HaveOccurred())
	Expect(cfg).ToNot(BeNil())

	err = cloudstatev1alpha1.AddToScheme(scheme.Scheme)
	Expect(err).NotTo(HaveOccurred())

	err = istio.AddToScheme(scheme.Scheme)
	Expect(err).NotTo(HaveOccurred())

	// +kubebuilder:scaffold:scheme

	k8sClient, err = client.New(cfg, client.Options{Scheme: scheme.Scheme})
	Expect(err).ToNot(HaveOccurred())
	Expect(k8sClient).ToNot(BeNil())

	controllerStopCh = runController(context.Background())

	close(done)
}, 60)

var _ = AfterSuite(func() {
	close(controllerStopCh)
	By("tearing down the test environment")
	err := testEnv.Stop()
	Expect(err).ToNot(HaveOccurred())
})

func createTestNamespace(ctx context.Context) string {
	name := fmt.Sprintf("test-%s", rand.String(6))
	namespace := &corev1.Namespace{
		ObjectMeta: metav1.ObjectMeta{Name: name},
	}
	Expect(k8sClient.Create(ctx, namespace)).To(Succeed(), "failed to create test namespace")
	return name
}

func deleteNamespace(ctx context.Context, name string) {
	namespace := &corev1.Namespace{
		ObjectMeta: metav1.ObjectMeta{Name: name},
	}
	Expect(k8sClient.Delete(ctx, namespace)).To(Succeed(), "failed to delete test namespace")
}

// RunController runs the controller manager and returns a stop channel for stopping it.
func runController(ctx context.Context) chan struct{} {
	stopCh := make(chan struct{})

	operatorConfig := &config.OperatorConfig{
		NoStore: config.NoStoreConfig{
			Image: "cloudstate-proxy-no-store",
		},
		InMemory: config.InMemoryConfig{
			Image: "cloudstate-proxy-in-memory",
		},
		Postgres: config.PostgresConfig{
			Image: "cloudstate-proxy-postgres",
			GoogleCloudSQL: config.PostgresGoogleCloudSQLConfig{
				Enabled: true,
			},
		},
	}
	config.SetDefaults(operatorConfig)

	mgr, err := ctrl.NewManager(cfg, ctrl.Options{})
	Expect(err).ToNot(HaveOccurred(), "failed to create controller manager")

	multistore := stores.DefaultMultiStores(mgr.GetClient(), mgr.GetScheme(), ctrl.Log.WithName("stores"),
		operatorConfig)

	err = (&StatefulStoreReconciler{
		Client:           mgr.GetClient(),
		Log:              ctrl.Log.WithName("controllers").WithName("StatefulStore"),
		Scheme:           mgr.GetScheme(),
		ReconcileTimeout: 10 * time.Second,
		OperatorConfig:   operatorConfig,
		Stores:           multistore,
	}).SetupWithManager(mgr)
	Expect(err).ToNot(HaveOccurred(), "failed to setup statefulstore controller")

	err = (&StatefulServiceReconciler{
		Client:           mgr.GetClient(),
		Log:              ctrl.Log.WithName("controllers").WithName("StatefulService"),
		Scheme:           mgr.GetScheme(),
		ReconcileTimeout: 10 * time.Second,
		OperatorConfig:   operatorConfig,
		Stores:           multistore,
	}).SetupWithManager(mgr)
	Expect(err).ToNot(HaveOccurred(), "failed to setup statefulservice controller")

	go func() {
		defer GinkgoRecover()
		Expect(mgr.Start(stopCh)).To(Succeed(), "failed to start controller manager")
	}()

	return stopCh
}

// waitforResources waits for the given resource to exist.
func waitForResource(ctx context.Context, namespace, name string, obj runtime.Object) {
	Eventually(func() error {
		return k8sClient.Get(ctx, client.ObjectKey{Name: name, Namespace: namespace}, obj)
	}, waitTimeout, pollInterval).Should(BeNil(), "%T %s/%s not found", obj, namespace, name)
}
