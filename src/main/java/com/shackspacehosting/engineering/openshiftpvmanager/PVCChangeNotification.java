package com.shackspacehosting.engineering.openshiftpvmanager;

import com.openshift.restclient.model.volume.IPersistentVolumeClaim;

import java.util.Map;
import java.util.Set;

public class PVCChangeNotification {

	public String updateType;
	public String namespace;
	public String name;
	public Set<String> accessModes;
	public String status;
	public String requestedStorage;
	public String volumeName;
	public Map<String, String> metadata;
	public Map<String, String> annotations;
	public Map<String, String> labels;

	public String getUpdateType() {
		return updateType;
	}

	public String getNamespace() {
		return namespace;
	}

	public String getName() {
		return name;
	}

	public Set<String> getAccessModes() {
		return accessModes;
	}

	public String getStatus() {
		return status;
	}

	public String getRequestedStorage() {
		return requestedStorage;
	}

	public String getVolumeName() {
		return volumeName;
	}

	public Map<String, String> getMetadata() {
		return metadata;
	}

	public Map<String, String> getAnnotations() {
		return annotations;
	}

	public Map<String, String> getLabels() {
		return labels;
	}

	public PVCChangeNotification(String updateType, IPersistentVolumeClaim pvc) {
		this.updateType = updateType;
		this.namespace = pvc.getNamespace();
		this.name = pvc.getName();
		this.accessModes = pvc.getAccessModes();
		this.status = pvc.getStatus();
		this.requestedStorage = pvc.getRequestedStorage();
		this.volumeName = pvc.getVolumeName();
		this.metadata = pvc.getMetadata();
		this.labels = pvc.getLabels();
		this.annotations = pvc.getAnnotations();
	}

	boolean Equals(Object o) {
		if (o == this) {
			return true;
		}

		if (!this.getClass().equals(o.getClass())) {
			return false;
		}

		PVCChangeNotification otherPVCChangeNotification = (PVCChangeNotification)o;
		if (!otherPVCChangeNotification.updateType.equals(this.updateType)) {
			return false;
		}

		if(!otherPVCChangeNotification.namespace.equals(this.namespace)) {
			return false;
		}
		if (!otherPVCChangeNotification.name.equals(this.name)) {
			return false;
		}
		if (!otherPVCChangeNotification.accessModes.equals(this.accessModes)) {
			return false;
		}
		if (!otherPVCChangeNotification.status.equals(this.status)) {
			return false;
		}
		if (!otherPVCChangeNotification.requestedStorage.equals(this.requestedStorage)) {
			return false;
		}
		if (!otherPVCChangeNotification.volumeName.equals(this.volumeName)) {
			return false;
		}
		if (!otherPVCChangeNotification.metadata.equals(this.metadata)) {
			return false;
		}
		if (!otherPVCChangeNotification.labels.equals(this.labels)) {
			return false;
		}
		if (!otherPVCChangeNotification.annotations.equals(this.annotations)) {
			return false;
		}

		return true;
	}
}
