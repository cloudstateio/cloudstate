package listeners

import (
	"context"
	cloudstate "github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/apis/v1alpha1"
	appsv1 "k8s.io/api/apps/v1"
)

type StatefulServiceListener interface {
	DeploymentCreated(ctx context.Context, service *cloudstate.StatefulService, created *appsv1.Deployment) error
	DeploymentUpdated(ctx context.Context, service *cloudstate.StatefulService, old *appsv1.Deployment, new *appsv1.Deployment) error
}

type NullStatefulServiceListener struct {
}

var _ StatefulServiceListener = (*NullStatefulServiceListener)(nil)

func (n *NullStatefulServiceListener) DeploymentCreated(ctx context.Context, service *cloudstate.StatefulService, created *appsv1.Deployment) error {
	return nil
}

func (n *NullStatefulServiceListener) DeploymentUpdated(ctx context.Context, service *cloudstate.StatefulService, old *appsv1.Deployment, new *appsv1.Deployment) error {
	return nil
}
