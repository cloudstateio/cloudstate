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

package main

import (
	"flag"
	"os"
	"time"

	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/config"
	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/controllers"
	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/stores"
	"github.com/ilyakaznacheev/cleanenv"
	"sigs.k8s.io/controller-runtime/pkg/webhook"

	cloudstatev1alpha1 "github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/apis/v1alpha1"
	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/webhooks"
	istio "istio.io/client-go/pkg/apis/networking/v1alpha3"
	"k8s.io/apimachinery/pkg/runtime"
	clientgoscheme "k8s.io/client-go/kubernetes/scheme"
	_ "k8s.io/client-go/plugin/pkg/client/auth/gcp"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/log/zap"
	// +kubebuilder:scaffold:imports
)

var (
	scheme   = runtime.NewScheme()
	setupLog = ctrl.Log.WithName("setup")
)

func init() {
	_ = clientgoscheme.AddToScheme(scheme)
	_ = cloudstatev1alpha1.AddToScheme(scheme)
	_ = istio.AddToScheme(scheme)
	// +kubebuilder:scaffold:scheme
}

// readConfigs reads configs from a config file.
// The default file path is "/etc/config/config.yaml". This can be overridden with the CONFIG_PATH environment variable.
// It is a fatal error if an explicit CONFIG_PATH is provided and not found.
// If the default path is not found, we go with default config settings.  (Transitional behaviour that may change.)
func readConfigs() *config.OperatorConfig {
	configPath, pathProvided := os.LookupEnv("CONFIG_PATH")
	if !pathProvided {
		configPath = "/etc/config/config.yaml"
	}
	cfg, err := config.ReadConfig(configPath)
	if err == nil {
		setupLog.Info("using config", "file", configPath)
		return cfg
	}
	if os.IsNotExist(err) {
		if pathProvided {
			setupLog.Error(err, "config file not found", "file", configPath)
			os.Exit(1)
		} // else we just go with the defaults.
		setupLog.Info("using default configs")
		var cfg config.OperatorConfig
		config.SetDefaults(&cfg)
		return &cfg
	}

	setupLog.Error(err, "error reading config file", "err", err)
	os.Exit(1)
	return nil
}

func main() {
	var (
		metricsAddr          string
		enableLeaderElection bool
		reconcileTimeout     time.Duration
		enableDebugLogs      bool
	)

	// Setting this here to catch logs from readConfig. Will reset as appropriate below.
	ctrl.SetLogger(zap.New(func(o *zap.Options) {
		o.Development = true
	}))

	cfg := readConfigs()

	// TODO: Move these two to config file?
	flag.StringVar(&metricsAddr, "metrics-addr", ":8080", "The address the metric endpoint binds to.")
	flag.BoolVar(&enableLeaderElection, "enable-leader-election", false,
		"Enable leader election for controller manager. Enabling this will ensure there is only one active controller manager.")
	flag.DurationVar(&reconcileTimeout, "reconcile-timeout", 60*time.Second, "the max time a reconcile loop can spend")
	flag.BoolVar(&enableDebugLogs, "debug", true, "Enable debug logs.")
	// Could override config or env setting with something like this...
	// flag.StringVar(&cfg.StatefulServiceConfig.InMemoryImageName, "inMemoryImageName", "somedefault", "name of inmemory image")

	// Adds documentation about possible env var settings.
	flag.Usage = cleanenv.FUsage(flag.CommandLine.Output(), &cfg, nil, flag.Usage)
	flag.Parse()

	ctrl.SetLogger(zap.New(func(o *zap.Options) {
		o.Development = enableDebugLogs
	}))

	mgr, err := ctrl.NewManager(ctrl.GetConfigOrDie(), ctrl.Options{
		Scheme:             scheme,
		MetricsBindAddress: metricsAddr,
		LeaderElection:     enableLeaderElection,
		LeaderElectionID:   "cloudstate-leader-election-helper",
		Port:               9443,
	})
	if err != nil {
		setupLog.Error(err, "unable to start manager")
		os.Exit(1)
	}

	store := stores.DefaultMultiStores(mgr.GetClient(), scheme, ctrl.Log, cfg)

	mgr.GetWebhookServer().Register("/inject-v1-pod",
		&webhook.Admission{Handler: webhooks.NewPodInjector(
			mgr.GetClient(),
			ctrl.Log.WithName("webhooks").WithName("PodInjector"),
			store,
			cfg,
		)})

	if err = (&controllers.StatefulStoreReconciler{
		Client:           mgr.GetClient(),
		Log:              ctrl.Log.WithName("controllers").WithName("StatefulStore"),
		Scheme:           mgr.GetScheme(),
		ReconcileTimeout: reconcileTimeout,
		OperatorConfig:   cfg,
		Stores:           store,
	}).SetupWithManager(mgr); err != nil {
		setupLog.Error(err, "unable to create controller", "controller", "StatefulStore")
		os.Exit(1)
	}
	if err = (&controllers.StatefulServiceReconciler{
		Client:           mgr.GetClient(),
		Log:              ctrl.Log.WithName("controllers").WithName("StatefulService"),
		Scheme:           mgr.GetScheme(),
		ReconcileTimeout: reconcileTimeout,
		OperatorConfig:   cfg,
		Stores:           store,
	}).SetupWithManager(mgr); err != nil {
		setupLog.Error(err, "unable to create controller", "controller", "StatefulService")
		os.Exit(1)
	}
	// +kubebuilder:scaffold:builder

	setupLog.Info("starting manager")
	if err := mgr.Start(ctrl.SetupSignalHandler()); err != nil {
		setupLog.Error(err, "problem running manager")
		os.Exit(1)
	}
}
