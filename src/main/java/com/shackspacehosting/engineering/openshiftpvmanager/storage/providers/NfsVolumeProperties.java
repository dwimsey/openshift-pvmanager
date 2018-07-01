package com.shackspacehosting.engineering.openshiftpvmanager.storage.providers;

public class NfsVolumeProperties {
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
