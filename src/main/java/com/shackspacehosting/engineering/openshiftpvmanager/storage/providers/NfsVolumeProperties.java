package com.shackspacehosting.engineering.openshiftpvmanager.storage.providers;

import io.kubernetes.client.models.V1NFSVolumeSource;

public class NfsVolumeProperties {
	public NfsVolumeProperties(V1NFSVolumeSource nfs) {
		this.nfsHostname = nfs.getServer();
		this.nfsExportPath = nfs.getPath();
		this.readOnly = ((nfs.isReadOnly() != null) ? nfs.isReadOnly() : false);
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

	public NfsVolumeProperties(String nfsHostname, String s, boolean readOnly) {
		this.nfsHostname = nfsHostname;
		this.nfsExportPath = s;
		this.readOnly = readOnly;
	}
}
