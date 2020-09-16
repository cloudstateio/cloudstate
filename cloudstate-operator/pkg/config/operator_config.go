package config

import (
	"github.com/ilyakaznacheev/cleanenv"
	"gopkg.in/yaml.v3"
)

type OperatorConfig struct {
	Configs   map[string]*yaml.Node `yaml:",inline"`
	NoStore   NoStoreConfig         `yaml:"noStore"`
	InMemory  InMemoryConfig        `yaml:"inMemory"`
	Cassandra CassandraConfig       `yaml:"cassandra"`
	Postgres  PostgresConfig        `yaml:"postgres"`
	Spanner   SpannerConfig         `yaml:"spanner"`
	Gcp       GcpConfig             `yaml:"gcp"`
}

type NoStoreConfig struct {
	Image string `yaml:"image" env:"NO_STORE_IMAGE"`
}

type InMemoryConfig struct {
	Image string `yaml:"image" env:"IN_MEMORY_IMAGE"`
}

type CassandraConfig struct {
	Image string `yaml:"image" env:"CASSANDRA_IMAGE"`
}

type PostgresConfig struct {
	Image string `yaml:"image" env:"POSTGRES_IMAGE"`

	GoogleCloudSql PostgresGoogleCloudSqlConfig `yaml:"googleCloudSql"`
}

type PostgresGoogleCloudSqlConfig struct {
	Enabled bool `yaml:"enabled" env:"POSTGRES_GOOGLE_CLOUD_SQL_ENABLED"`

	// This is the DB type and version as used by GCP.  Must start with "POSTGRES_" to ensure we get a Postgres DB.
	TypeVersion   string `yaml:"typeVersion" env:"POSTGRES_GOOGLE_CLOUD_SQL_TYPE_VERSION" env-description:"DB descriptor for GCP"`
	Region        string `yaml:"region" env:"POSTGRES_GOOGLE_CLOUD_SQL_REGION" env-description:"GCP region for instances/databases"`
	DefaultCores  int32  `yaml:"defaultCores" env:"POSTGRES_GOOGLE_CLOUD_SQL_DEFAULT_CORES" env-description:"default number of cores for Postgres SQLInstances"`
	DefaultMemory string `yaml:"defaultMemory" env:"POSTGRES_GOOGLE_CLOUD_SQL_DEFAULT_MEMORY" env-description:"default memory allocation (MiB) for Postgres SQLInstances"`
}

type SpannerConfig struct {
	Image string `yaml:"image" env:"SPANNER_IMAGE"`
}

type GcpConfig struct {
	Project string `yaml:"project" env:"GCP_PROJECT"`
	Network string `yaml:"network" env:"GCP_NETWORK"`
}

func (o *OperatorConfig) Get(key string, obj interface{}) error {
	if o.Configs[key] != nil {
		if err := o.Configs[key].Decode(obj); err != nil {
			return err
		}
		return cleanenv.ReadEnv(obj)
	}
	return nil
}

func ReadConfig(path string) (*OperatorConfig, error) {
	config := &OperatorConfig{}
	SetDefaults(config)
	if err := cleanenv.ReadConfig(path, config); err != nil {
		return nil, err
	}
	return config, nil
}

func SetDefaults(config *OperatorConfig) {
	config.Postgres.GoogleCloudSql.TypeVersion = "POSTGRES_11"
	config.Postgres.GoogleCloudSql.Region = "us-east1"
	config.Postgres.GoogleCloudSql.DefaultCores = 1
	config.Postgres.GoogleCloudSql.DefaultMemory = "3840"
}
