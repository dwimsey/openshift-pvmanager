package com.shackspacehosting.engineering.pvmanager.kubernetes;

import io.kubernetes.client.models.V1PersistentVolumeSpec;

import java.util.List;
import java.util.Map;

public class PVChangeNotification {

	final private String name;
	final private String kind;
	final private String changeType;
	final private String pvState;
	final private String pvStateMessage;
	final private String pvStateReason;
	final private List<String> accessModes;
	final private Map<String, String> annotations;
	final private Map<String, String> labels;
	final private V1PersistentVolumeSpec claimSpec;
	final private String reclaimPolicy;

	public PVChangeNotification(String name, String kind, String changeType, String pvState, String pvStateMessage, String pvStateReason, List<String> accessModes, Map<String, String> annotations, Map<String, String> labels, V1PersistentVolumeSpec claimSpec, String reclaimPolicy) {
		this.changeType = changeType;
		this.pvState = pvState;
		this.pvStateMessage = pvStateMessage;
		this.pvStateReason = pvStateReason;
		this.name = name;
		this.kind = kind;
		this.accessModes = accessModes;
		this.annotations = annotations;
		this.labels = labels;
		this.claimSpec = claimSpec;
		this.reclaimPolicy = reclaimPolicy;
	}

	boolean Equals(Object o) {
		if (o == this) {
			return true;
		}

		if (!this.getClass().equals(o.getClass())) {
			return false;
		}

		PVChangeNotification otherPVCChangeNotification = (PVChangeNotification)o;
		if (!otherPVCChangeNotification.pvState.equals(this.pvState)) {
			return false;
		}
		if (!otherPVCChangeNotification.name.equals(this.name)) {
			return false;
		}
		if (!otherPVCChangeNotification.accessModes.equals(this.accessModes)) {
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

	public String getKind() {
		return kind;
	}

	public String getChangeType() {
		return changeType;
	}

	public String getPvState() {
		return pvState;
	}

	public String getPvStateMessage() {
		return pvStateMessage;
	}

	public String getPvStateReason() {
		return pvStateReason;
	}

	public String getName() {
		return name;
	}

	public List<String> getAccessModes() {
		return accessModes;
	}

	public Map<String, String> getAnnotations() {
		return annotations;
	}

	public Map<String, String> getLabels() {
		return labels;
	}

	public V1PersistentVolumeSpec getClaimSpec() {
		return claimSpec;
	}

	public String getReclaimPolicy() {
		return reclaimPolicy;
	}
}
