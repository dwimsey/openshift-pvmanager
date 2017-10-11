OpenShift Persistent Volume Manager
===================================

This application is currently a dirty hack to provide automatic creation of persistent volumes in OpenShift Origin
environments using NFS to connect to ZFS backed storage volumes.

The basic idea is that PVManager watches an OpenShift cluster, looking for persistent volume claims that have not been
fulfilled and then creates a persistent volume that matches the requirements provided for size and access modes.

This is currently just a stub of a real application and needs a fair amount of work to be something used in production.


Installation
```
# Build and push the openshift-pvmanager Docker image to an image registry location of your choosing:
mvn clean deploy -Ddocker.registry.base=your-registry-url.com/openshift-infra

# Create a service account for the pvmanager service to run as
oc create sa pvmanager -n <projectname>

# Grant proper role to pvmanager (This isn't the right role, we need to make a new one with more limited access, cluster-admin has free reign over the entire cluster)
# This command must be run as a user with the cluster-admin role.
# https://blog.openshift.com/understanding-service-accounts-sccs/
oc adm policy add-cluster-role-to-user cluster-admin system:serviceaccount:<projectname>:pvmanager

# Create a deployment of the image
oc create -f openshift-pvmanager.yml -n <projectname>

# Switch the custom service account for
oc patch dc/openshift-pvmanager --patch '{"spec":{"template":{"spec":{"serviceAccountName": "pvmanager"}}}}' -n <projectname>

oc deploy dc/openshift-pvmanager
```
