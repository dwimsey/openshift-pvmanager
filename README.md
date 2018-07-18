OpenShift Persistent Volume Manager
===================================

### Overview

pvmanager provides automatic creation and deletion of persistent volumes on Kubernetes clusters using NFS and ZFS.

By maintaining `watches` on the Kubernetes API pvmanager detects 
creates, modification and deletion of persistent volumes and persistent
volume claims in real time.

#### Annotations

Users may control certain aspects of the create created filesystem using
annotations when creating persistent volume claims.

Applications may take advantage of instant file system clones using the clone-from annotation to clone an existing PVC.

Users may control some aspects of how the ZFS filesystem behaves using the following annotations:

|Annotation|Default|Description|
|----------|-------|-----------|
|`pvmanager.wimsey.us/blocksize` | *128K* | Prefered blocksize on disk |
|`pvmanager.wimsey.us/checksum` | *on* | Determine how checksuming is performed on this filesystem |
|`pvmanager.wimsey.us/compression` | *off* | Set the ZFS compression setting for this filesystem |
|`pvmanager.wimsey.us/atime` | *on* | Determine if atime is enabled on this filesystem |
|`pvmanager.wimsey.us/exec` | *on* | Determine if the execute bit is disabled for this filesystem |
|`pvmanager.wimsey.us/setuid` | *on* | Determine if the set uid bit is disabled for this filesystem |
|`pvmanager.wimsey.us/logbias` | *throughput* | Determine if this filesystem prefers 'throughput' or 'latency' |
|`pvmanager.wimsey.us/snapdir` | *hidden* | Controls whether the `.zfs` directory is `hidden` or `visible` in the root of the file system. The default value is hidden. Determine how the snapshot directory for this file system is exposed |
|`pvmanager.wimsey.us/sync` | *standard* | Determines how fsync is handled for this filesystem |
|`pvmanager.wimsey.us/casesensitive` | *casesensitive* | Determines if this filesystem is case sensitive |
|`pvmanager.wimsey.us/reclaim-policy` | *Delete* | Override default value for reclaim policy setting for persistent volumes created by pvmanager |
|`pvmanager.wimsey.us/clone-from` | *example-pvc* | When specified on a PVC, pvmanager will look for a PVC matching the name specified in this value within the same namespace.  If a match is found and exists on a compatible ZFS root the specfied PVC will have a snapshot created and a clone filesystem created for this mount. |

### Reclaim Policy 

pvmanager does not use the Kubernetes built in reclaim system for persistent volumes because it does not
integrate with Kubernetes in process and is an out of band solution.  Reclaim policy functionality is emulated
however to perform effectively the same was built in methods.

#### Delete

As soon as pvmanager detects a persistent volume in a released state, it is
deleted and its resources are released where possible.  If the persistent
volume had any clones, the persistent volume will be deleted
from Kubernetes but the file system resources will remain until they can
be scavanged after all clones are deleted.

#### Recycle

* Recycle is not supported at this time.

#### Retain 

Keep persistent volumes after a persistent volume claim releases them.

When pvmanager operates in this mode, persistent volumes will not be deleted
by pvmanager after they are released.

#### Snapshots and Clones

pvmanager supports cloning of pvmanager persistent volumes by setting the
the `pvmanager.wimsey.us/clone-from` annotation to the name of an existing
persistent volume claim to clone when creating a new persistent volume.

To clone an existing filesystem, first a snapshot of the existing filesystem
is taken.  From that snapshot, the clone is created and mounted to the Kubernetes
environment.  The snapshot and clone are dependents of the existing filesystem,
as long as the snapshot and clone exists, the parent filesystem can not be
released.  The original persistent volume claim and persistent volume may
be deleted, but only after all dependents have been deleted and the parent
filesystem is scavanaged will associated resources be released back to the pool.

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
ssh-keygen -f pvmanager_id_rsa -N "${PVMAN_PSWD}"

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
