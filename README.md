OpenShift Persistent Volume Manager
===================================

This application is currently a dirty hack to provide automatic creation of persistent volumes in OpenShift Origin
environments using NFS to connect to ZFS backed storage volumes.

The basic idea is that PVManager watches an OpenShift cluster, looking for persistent volume claims that have not been
fulfilled and then creates a persistent volume that matches the requirements provided for size and access modes.

This is currently just a stub of a real application and needs a fair amount of work to be something used in production.



Installation
```
# The namespace we're going to install pvmanager into
NAMESPACE=ops

# The nfs host which will server our zfs file system.  Only one used in this example simplicity
NFSHOST=10.27.100.5

# The username to provider to ssh for use on the nfs host 
NFSUSER=pvmanager

# Password used for pvmanger SSH key, should generate this randomly
PVMAN_PSWD=someRandomPass4Me!

# Build and push the openshift-pvmanager Docker image to an image registry location of your choosing:
#mvn clean deploy -Ddocker.registry.base=your-registry-url.com/openshift-infra

#### Setup NFS/ZFS server with an account
# Create an account on the NFS host for pvmanager to use to run ZFS commands (FreeBSD NFS/ZFS host)
ssh ${NFSHOST} sudo pw user add ${NFSUSER}

# Create ssh key for 
ssh-keygen -f pvmanager_id_rsa

# Create .ssh directory for pvmanager account
ssh ${NFSHOST} sudo mkdir -p \~${NFSUSER}/.ssh

# Copy SSH key to remote host
scp pvmanager_id_rsa.pub ${NFSHOST}:\~${NFSUSER}/.ssh/id_rsa.pub

# Fix permissions on ssh files
ssh ${NFSHOST} sudo chmod 0750 \~${NFSUSER}/.ssh
ssh ${NFSHOST} sudo chmod 0644 \~${NFSUSER}/.ssh/id_rsa.pub

# Fix owner on ssh files
ssh ${NFSHOST} sudo chown pvmanager -R \~${NFSUSER}/.ssh



#### Setup ZFS filesystem root for storage class
ssh ${NFSHOST} sudo zfs create -o mountpoint=/exports/openshift/persistentvolumes/basic -o "sharenfs=-maproot=0" zpool/openshift_basic
ssh ${NFSHOST} sudo zfs allow ${NFSUSER} create,destroy,mount,sharenfs,snapshot,clone zpool/openshift_basic


#### Deploy kubernetes/OpenShift assets
# Create a new project for pvmanager
oc new-project ${NAMESPACE}

# Ensure you've switched to the right project
oc project ${NAMESPACE}

# Create a service account for the pvmanager service to run as
oc create sa pvmanager -n ${NAMESPACE}

# Grant proper role to pvmanager (This isn't the right role, we need to make a new one with more limited access, cluster-admin has free reign over the entire cluster)
# This command must be run as a user with the cluster-admin role.
# https://blog.openshift.com/understanding-service-accounts-sccs/
oc adm policy add-cluster-role-to-user cluster-admin system:serviceaccount:${NAMESPACE}:pvmanager

# Create the default configuration configmap
oc create -f kubernetes-templates/configmaps/pvmanager-configmap.yml -n ${NAMESPACE}

# Create the ssh secret
oc create -f kubernetes-templates/secrets/pvmanager-sshconfig.yml -n ${NAMESPACE}

# Create a deployment of the image
oc create -f kubernetes-templates/deployments/openshift-pvmanager.yml -n ${NAMESPACE}

# Switch the custom service account for
oc patch dc/pvmanager --patch '{"spec":{"template":{"spec":{"serviceAccountName": "pvmanager"}}}}' -n ${NAMESPACE}

# At this point, pvmanager should be starting on your cluster and beginning to fulfill persistent volume claim requests.

```
