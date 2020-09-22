package reconciliation

import (
	"crypto/rand"
	"fmt"
	"github.com/banzaicloud/k8s-objectmatcher/patch"
	"github.com/go-logr/logr"
	"golang.org/x/net/context"
	"k8s.io/apimachinery/pkg/api/meta"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/runtime/schema"
	k8srand "k8s.io/apimachinery/pkg/util/rand"
	"sigs.k8s.io/controller-runtime/pkg/client"

	cloudstate "github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/apis/v1alpha1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

const (
	NameLabel      = "app.kubernetes.io/name"
	componentLabel = "app.kubernetes.io/component"
	managedByLabel = "app.kubernetes.io/managed-by"
)

var (
	// Default value indicates no override.  cf. comments in randomID()
	OverrideID string = ""
)

/*
GetControlledUnstructured returns a list of (unstructured) objects with ownedGVK that are owned and controlled by
the object with ownerName and ownerGVK in ownerNamespace.  r is the reconciler involved.  List is pruned to no
more than a single object if prune is true.
*/
func GetControlledUnstructured(ctx context.Context, r client.Client, ownerName string, ownerNamespace string, ownerGVK schema.GroupVersionKind, ownedGVK schema.GroupVersionKind, prune bool) ([]unstructured.Unstructured, error) {
	uList := &unstructured.UnstructuredList{}
	uList.SetGroupVersionKind(ownedGVK)

	if err := r.List(ctx, uList, client.InNamespace(ownerNamespace)); err != nil {
		return nil, err
	}

	var ownedObjects = []unstructured.Unstructured{}
	var mostRecent *unstructured.Unstructured = nil
	for _, object := range uList.Items {
		// Can't use metav1.GetControllerOf(object) cuz it doesn't work with unstructured arg
		// if o := metav1.GetControllerOf(object); o != nil {
		//      ...
		// }
		ownerAPIVersion, _ := ownerGVK.ToAPIVersionAndKind()
		refs := object.GetOwnerReferences()
		if len(refs) > 0 {
			for _, o := range refs {
				if o.Controller != nil && *o.Controller &&
					o.Kind == ownerGVK.Kind &&
					o.APIVersion == ownerAPIVersion &&
					o.Name == ownerName {

					if !prune {
						ownedObjects = append(ownedObjects, object)
					} else {
						if mostRecent == nil {
							mostRecent = &object
							ownedObjects = append(ownedObjects, object)
						} else {
							old := &object
							a, _ := meta.Accessor(&object)
							b, _ := meta.Accessor(mostRecent)
							if a.GetCreationTimestamp().After(b.GetCreationTimestamp().Time) {
								old = mostRecent
								mostRecent = &object
								ownedObjects[0] = object
							}

							if err := r.Delete(ctx, old); err != nil {
								acc, _ := meta.Accessor(old)
								return nil, fmt.Errorf("unable to prune obsolete object %v/%v: %w", acc.GetName(), acc.GetNamespace(), err)
							}
						}
					}

					break // reasonable since k8s guarantees only one controller owner ref
				}
			}
		}
	}

	return ownedObjects, nil
}

/* GetControlledStructured uses context ctx and client cl to find an object of the same (non-list) type as ownedList that
is owned AND controlled by owner.  The owned type must be a structured type.  (Controlling owners must match the kind,
apiversion and name of owner.)  ownedList is only used internally.
*/
func GetControlledStructured(
	ctx context.Context,
	cl client.Client,
	owner runtime.Object,
	ownedList runtime.Object,
) (runtime.Object, error) {
	if !meta.IsListType(ownedList) {
		return nil, fmt.Errorf("ownedList must be a list type: %v", ownedList)
	}

	ownerAcc, err := meta.Accessor(owner)
	if err != nil {
		return nil, fmt.Errorf("unable to get meta accessor for owner: %w", err)
	}
	if err := cl.List(ctx, ownedList, client.InNamespace(ownerAcc.GetNamespace())); err != nil {
		return nil, fmt.Errorf("unable to list owned objects: %w", err)
	}

	objs, err := meta.ExtractList(ownedList)
	if err != nil {
		return nil, fmt.Errorf("unable to convert list to array: %w", err)
	}

	ownerTAcc, err := meta.TypeAccessor(owner)
	if err != nil {
		return nil, fmt.Errorf("unable to get type accessor for owner: %w", err)
	}

	var ownedObjects = []runtime.Object{}
	for _, object := range objs {
		objAcc, err := meta.Accessor(object)
		if err != nil {
			return nil, fmt.Errorf("unable to get meta accessor for object: %w", err)
		}
		if o := metav1.GetControllerOf(objAcc); o != nil {
			if o.Kind == ownerTAcc.GetKind() &&
				o.APIVersion == ownerTAcc.GetAPIVersion() &&
				o.Name == ownerAcc.GetName() {

				ownedObjects = append(ownedObjects, object)
			}
		}
	}

	// Todo:  Why do we return only one?  What if controller manages two or more of same kind?
	switch len(ownedObjects) {
	case 0:
		// no owned objects of this type
	case 1:
		// single owned object
		return ownedObjects[0], nil
	default:
		err = meta.SetList(ownedList, ownedObjects)
		if err != nil {
			panic(err)
		}
		if obj, err := getMostRecentAndPruneRest(ctx, cl, ownedList); err == nil {
			return obj, nil
		} else {
			return nil, err
		}
	}

	return nil, nil
}

// getMostRecentAndPruneRest takes a list of objects, returns the most recent of them, and deletes the remaining items.
// This is useful when reconciling a list which is expected to only have a single item.
func getMostRecentAndPruneRest(
	ctx context.Context,
	cl client.Client,
	objectList runtime.Object,
) (runtime.Object, error) {

	if !meta.IsListType(objectList) {
		return nil, fmt.Errorf("expected %v to be a list", objectList)
	}
	if meta.LenList(objectList) == 0 {
		return nil, fmt.Errorf("expected non-empty list")
	}

	objs, _ := meta.ExtractList(objectList)

	mostRecent := objs[0]
	for i := 1; i < len(objs); i++ {
		a, _ := meta.Accessor(objs[i])
		b, _ := meta.Accessor(mostRecent)
		if a.GetCreationTimestamp().After(b.GetCreationTimestamp().Time) {
			mostRecent = objs[i]
		}
	}

	for _, obj := range objs {
		if obj != mostRecent {
			if err := cl.Delete(ctx, obj); err != nil {
				acc, _ := meta.Accessor(obj)
				return nil, fmt.Errorf("unable to prune obsolete deployment %v/%v: %w", acc.GetName(), acc.GetNamespace(), err)
			}
		}
	}

	return mostRecent, nil
}

// SetLastApplied sets the last applied annotation to the value of the desired object.
func SetLastApplied(desired runtime.Object) {
	if err := patch.DefaultAnnotator.SetLastAppliedAnnotation(desired); err != nil {
		panic(err)
	}
}

// NeedsUpdate returns true if the actual object state has deviated from desired, indicating
// the actual should be updated.  It will return false if the object is known to be in the
// process of being deleted.
func NeedsUpdate(log logr.Logger, actual, desired runtime.Object) bool {
	if !lastAppliedConfigExists(desired) {
		// Precondition is that the lastApplied annotation is set on desired.
		panic("lastApplied was not set on desired")
	}

	if deletionTimestampExists(actual) {
		return false
	}

	if !lastAppliedConfigExists(actual) {
		// No lastApplied annotation on actual state - update is needed.
		return true
	}

	patchResult, err := patch.DefaultPatchMaker.Calculate(actual, desired)
	if err != nil {
		panic(err)
	}

	if !patchResult.IsEmpty() {
		log.V(1).Info("changed", "patchResult", patchResult.String())
		return true
	}
	return false
}

func lastAppliedConfigExists(obj runtime.Object) bool {
	anno, err := meta.NewAccessor().Annotations(obj)
	if err != nil {
		panic(err)
	}
	_, exists := anno[patch.LastAppliedConfig]
	return exists
}

func deletionTimestampExists(obj runtime.Object) bool {
	a, err := meta.Accessor(obj)
	if err != nil {
		panic(err)
	}
	return !a.GetDeletionTimestamp().IsZero()
}

// GenerateRandomBytes returns n securely generated random bytes.
// It will return an error if the system's secure random
// number generator fails to function correctly, in which
// case the caller should not continue.
func GenerateRandomBytes(n int) ([]byte, error) {
	b := make([]byte, n)
	_, err := rand.Read(b)
	// Note that err == nil only if we read len(b) bytes.
	if err != nil {
		return nil, err
	}

	return b, nil
}

// RandomID generates a 4 character string valid for k8s name components.
// This can be overridden by giving OverrideID a non-empty value.  This feature is intended for use only with tests.
func RandomID() string {
	if OverrideID != "" {
		return OverrideID
	} else {
		return k8srand.String(4)
	}
}

func SetCommonLabels(name string, labels map[string]string) map[string]string {
	labels[NameLabel] = name
	labels[componentLabel] = "user-function"
	labels[managedByLabel] = "cloudstate-operator"
	return labels
}

// if any of the conditions have changed, returns the list of conditions, otherwise returns an empty slice
func ReconcileStatefulServiceConditions(existing, conditions []cloudstate.CloudstateCondition) []cloudstate.CloudstateCondition {
	changed := false
	cmap := make(map[cloudstate.CloudstateConditionType]bool, len(conditions))
	for _, condition := range conditions {
		cmap[condition.Type] = true
		found := false
		for idx := range existing {
			if condition.Type == existing[idx].Type {
				found = true
				if condition.Status != existing[idx].Status ||
					condition.Reason != existing[idx].Reason ||
					condition.Message != existing[idx].Message {
					changed = true
					existing[idx].Status = condition.Status
					existing[idx].Reason = condition.Reason
					existing[idx].Message = condition.Message
					existing[idx].LastTransitionTime = metav1.Now()
				}
				break
			}
		}
		if !found {
			changed = true
			condition.LastTransitionTime = metav1.Now()
			existing = append(existing, condition)
		}
	}

	// Every condition from conditions is now in existing, so if they don't have the same length, that means that
	// there are conditions in existing that need to be deleted.
	if len(conditions) != len(existing) {
		changed = true
		filtered := make([]cloudstate.CloudstateCondition, len(conditions))
		for idx := range existing {
			if cmap[existing[idx].Type] {
				filtered[idx] = existing[idx]
			}
		}
		existing = filtered
	}

	if changed {
		return existing
	}
	return nil
}
