package com.shackspacehosting.engineering.pvmanager.storage.providers;

import io.kubernetes.client.models.V1NFSVolumeSource;

public class NfsVolumeProperties {
	public NfsVolumeProperties(V1NFSVolumeSource nfs) {
		this.nfsHostname = nfs.getServer();
		this.nfsExportPath = nfs.getPath();
		this.readOnly = ((nfs.isReadOnly() != null) ? nfs.isReadOnly() : false);
		this.pvName = null;
		this.reclaimPolicy = null;
	}

	public String getNfsHostname() {
		return nfsHostname;
	}

	public String getNfsExportPath() {
		return nfsExportPath;
	}

	public boolean isReadOnly() {
		return readOnly;
	}

	final String nfsHostname;
	final String nfsExportPath;
	final boolean readOnly;
	String pvName;
	String reclaimPolicy;

	public NfsVolumeProperties(String nfsHostname, String s, boolean readOnly, String namePrefix) {
		this.nfsHostname = nfsHostname;
		this.nfsExportPath = s;
		this.readOnly = readOnly;
		this.pvName = namePrefix;
	}

	public String getPVName() {
		if(pvName == null) {
			return "";
		}
		return pvName;
	}

	public void setPVName(String namePrefix) {
		this.pvName = namePrefix;
	}

	public String getProviderReclaimPolicy() {
		return this.reclaimPolicy;
	}
	public void setProviderReclaimPolicy(String reclaimPolicy) {
		this.reclaimPolicy = reclaimPolicy;
	}

}
