OpenShift Persistent Volume Manager
===================================

This application is provides automatic creation and deletion of persistent volumes on Kubernetes clusters using NFS and ZFS.

On startup several connections are made to the Kubernetes API to watch for changes to persistent volumes and persistent volume
claims.  When a change occurs, pvmanager updates a persistent volume to match the required configuration.  Users may control
created filesystem using annotations when creating a persistent volume claim.

Applications may take advantage of instant file system clones using the clone-from annotation to clone an existing PVC.

Users may control some aspects of how the ZFS filesystem behaves using the following annotations:

|Annotation|Default|Description|
|----------|-------|-----------|
|`pvmanager.wimsey.us/blocksize` | *1024* | Prefered blocksize on disk |
|`pvmanager.wimsey.us/checksum` | *off* | Determine how checksuming is performed on this filesystem |
|`pvmanager.wimsey.us/compression` | *gzip-9* | Set the ZFS compression setting for this filesystem |
|`pvmanager.wimsey.us/atime` | *off* | Determine if atime is enabled on this filesystem |
|`pvmanager.wimsey.us/exec` | *off* | Determine if the execute bit is disabled for this filesystem |
|`pvmanager.wimsey.us/logbias` | *throughput* | Determine if this filesystem prefers 'throughput' or 'latency' |
|`pvmanager.wimsey.us/snapdir` | *hidden* | Determine how the snapshot directory for this file system is exposed |
|`pvmanager.wimsey.us/sync` | *off* | Determines how fsync is handled for this filesystem |
|`pvmanager.wimsey.us/casesensitive` | *insensitive* | Determines if this filesystem is case sensitive |
|`pvmanager.wimsey.us/clone-from` | *example-pvc* | When specified on a PVC, pvmanager will look for a PVC matching the name specified in this value within the same namespace.  If a match is found and exists on a compatible ZFS root the specfied PVC will have a snapshot created and a clone filesystem created for this mount. |


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
