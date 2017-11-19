package com.shackspacehosting.engineering.openshiftpvmanager;

import com.openshift.internal.restclient.model.volume.property.NfsVolumeProperties;
import com.openshift.restclient.model.volume.IPersistentVolume;
import com.openshift.restclient.model.volume.IPersistentVolumeClaim;
import com.openshift.restclient.model.volume.property.IPersistentVolumeProperties;

import java.util.Map;
import java.util.Set;

public class PVChangeNotification {

	public String updateType;
	public String namespace;
	public String name;
	public Set<String> accessModes;
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

	public Map<String, String> getMetadata() {
		return metadata;
	}

	public Map<String, String> getAnnotations() {
		return annotations;
	}

	public Map<String, String> getLabels() {
		return labels;
	}

	public PVChangeNotification(String updateType, IPersistentVolume pv) {
		this.updateType = updateType;
		this.namespace = pv.getNamespace();
		this.name = pv.getName();
		this.accessModes = pv.getAccessModes();
		this.metadata = pv.getMetadata();
		this.labels = pv.getLabels();
		this.annotations = pv.getAnnotations();
		//NfsVolumeProperties nfsVolumeProperties = (NfsVolumeProperties)pv.getPersistentVolumeProperties();
	}

	boolean Equals(Object o) {
		if (o == this) {
			return true;
		}

		if (!this.getClass().equals(o.getClass())) {
			return false;
		}

		PVChangeNotification otherPVCChangeNotification = (PVChangeNotification)o;
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
