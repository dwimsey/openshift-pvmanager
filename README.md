OpenShift Persistent Volume Manager
===================================

This application is currently a dirty hack to provide automatic creation of persistent volumes in OpenShift Origin
environments using NFS to connect to ZFS backed storage volumes.

The basic idea is that PVManager watches an OpenShift cluster, looking for persistent volume claims that have not been
fulfilled and then creates a persistent volume that matches the requirements provided for size and access modes.

This is currently just a stub of a real application and needs a fair amount of work to be something used in production.
