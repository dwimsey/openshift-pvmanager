package com.shackspacehosting.engineering.pvmanager.kubernetes;

//import com.openshift.restclient.model.volume.IPersistentVolumeClaim;

import io.kubernetes.client.models.V1PersistentVolumeClaimSpec;

import java.math.BigDecimal;
import java.util.Map;

public class PVCChangeNotification {

	public final String namespace;
	public final V1PersistentVolumeClaimSpec claimSpec;
	public final String status;
	public final BigDecimal requestedStorage;
	public final String volumeName;
	public final Map<String, String> annotations;
	public final Map<String, String> labels;

	public String getClaimUid() {
		return claimUid;
	}

	public final String claimUid;

	public String getChangeType() {
		return changeType;
	}

	public final String changeType;

	public String getNamespace() {
		return namespace;
	}

	public V1PersistentVolumeClaimSpec getClaimSpec() {
		return claimSpec;
	}

	public String getStatus() {
		return status;
	}

	public BigDecimal getRequestedStorage() {
		return requestedStorage;
	}

	public String getVolumeName() {
		return volumeName;
	}

	public Map<String, String> getAnnotations() {
		return annotations;
	}

	public Map<String, String> getLabels() {
		return labels;
	}

	public PVCChangeNotification(String namespace, String volumeName, V1PersistentVolumeClaimSpec claimSpec, Map<String, String> labels, Map<String, String> annotations, BigDecimal requestedStorage, String status, String changeType, String claimUid) {
		this.namespace = namespace;
		this.volumeName = volumeName;
		this.claimSpec = claimSpec;
		this.status = status;
		this.requestedStorage = requestedStorage;
		this.labels = labels;
		this.annotations = annotations;
		this.changeType = changeType;
		this.claimUid = claimUid;
	}

	boolean Equals(Object o) {
		if (o == this) {
			return true;
		}

		if (!this.getClass().equals(o.getClass())) {
			return false;
		}

		PVCChangeNotification otherPVCChangeNotification = (PVCChangeNotification)o;
		if(!otherPVCChangeNotification.namespace.equals(this.namespace)) {
			return false;
		}
		if (!otherPVCChangeNotification.claimSpec.equals(this.claimSpec)) {
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
		if (!otherPVCChangeNotification.labels.equals(this.labels)) {
			return false;
		}
		if (!otherPVCChangeNotification.annotations.equals(this.annotations)) {
			return false;
		}

		if(!otherPVCChangeNotification.changeType.equals(this.changeType)) {
			return false;
		}

		return true;
	}
}
