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
	"github.com/ilyakaznacheev/cleanenv"
	"gopkg.in/yaml.v3"
)

type OperatorConfig struct {
	Configs        map[string]*yaml.Node `yaml:",inline"`
	NoStore        NoStoreConfig         `yaml:"noStore"`
	InMemory       InMemoryConfig        `yaml:"inMemory"`
	Cassandra      CassandraConfig       `yaml:"cassandra"`
	Postgres       PostgresConfig        `yaml:"postgres"`
	Spanner        SpannerConfig         `yaml:"spanner"`
	GCP            GCPConfig             `yaml:"gcp"`
	SidecarMetrics SidecarMetricsConfig  `yaml:"sidecarMetrics"`
}

type NoStoreConfig struct {
	Image string   `yaml:"image" env:"NO_STORE_IMAGE"`
	Args  []string `yaml:"args"`
}

type InMemoryConfig struct {
	Image string   `yaml:"image" env:"IN_MEMORY_IMAGE"`
	Args  []string `yaml:"args"`
}

type CassandraConfig struct {
	Image string   `yaml:"image" env:"CASSANDRA_IMAGE"`
	Args  []string `yaml:"args"`
}

type PostgresConfig struct {
	Image string   `yaml:"image" env:"POSTGRES_IMAGE"`
	Args  []string `yaml:"args"`

	GoogleCloudSQL PostgresGoogleCloudSQLConfig `yaml:"googleCloudSql"`
}

type PostgresGoogleCloudSQLConfig struct {
	Enabled bool `yaml:"enabled" env:"POSTGRES_GOOGLE_CLOUD_SQL_ENABLED"`

	// This is the DB type and version as used by GCP.  Must start with "POSTGRES_" to ensure we get a Postgres DB.
	TypeVersion   string `yaml:"typeVersion" env:"POSTGRES_GOOGLE_CLOUD_SQL_TYPE_VERSION" env-description:"DB descriptor for GCP"`
	Region        string `yaml:"region" env:"POSTGRES_GOOGLE_CLOUD_SQL_REGION" env-description:"GCP region for instances/databases"`
	DefaultCores  int32  `yaml:"defaultCores" env:"POSTGRES_GOOGLE_CLOUD_SQL_DEFAULT_CORES" env-description:"default number of cores for Postgres SQLInstances"`
	DefaultMemory string `yaml:"defaultMemory" env:"POSTGRES_GOOGLE_CLOUD_SQL_DEFAULT_MEMORY" env-description:"default memory allocation (MiB) for Postgres SQLInstances"`
}

type SpannerConfig struct {
	Image string   `yaml:"image" env:"SPANNER_IMAGE"`
	Args  []string `yaml:"args"`
}

type GCPConfig struct {
	Project string `yaml:"project" env:"GCP_PROJECT"`
	Network string `yaml:"network" env:"GCP_NETWORK"`
}

func (c *OperatorConfig) Get(key string, obj interface{}) error {
	if _, ok := c.Configs[key]; !ok {
		return nil
	}
	if err := c.Configs[key].Decode(obj); err != nil {
		return err
	}
	return cleanenv.ReadEnv(obj)
}

type SidecarMetricsConfig struct {
	Port int32 `yaml:"port" env:"SIDECAR_METRICS_PORT"`
}

func ReadConfig(path string) (*OperatorConfig, error) {
	var config OperatorConfig
	SetDefaults(&config)
	if err := cleanenv.ReadConfig(path, &config); err != nil {
		return nil, err
	}
	return &config, nil
}

func SetDefaults(config *OperatorConfig) {
	config.Postgres.GoogleCloudSQL.TypeVersion = "POSTGRES_11"
	config.Postgres.GoogleCloudSQL.Region = "us-east1"
	config.Postgres.GoogleCloudSQL.DefaultCores = 1
	config.Postgres.GoogleCloudSQL.DefaultMemory = "3840"
	config.SidecarMetrics.Port = 9099

	config.NoStore.Args = []string{
		"-Dconfig.resource=no-store.conf",
	}
	config.InMemory.Args = []string{
		"-Dconfig.resource=in-memory.conf",
	}
}
