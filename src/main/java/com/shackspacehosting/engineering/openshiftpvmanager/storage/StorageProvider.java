package com.shackspacehosting.engineering.openshiftpvmanager.storage;

import com.shackspacehosting.engineering.openshiftpvmanager.storage.providers.IStorageManagementProvider;
import com.shackspacehosting.engineering.openshiftpvmanager.storage.providers.NfsVolumeProperties;

import java.util.Map;
import java.util.UUID;

public class StorageProvider {
	String storageClass;
	public String getStorageClass() {
		return storageClass;
	}

	public void setStorageClass(String storageClass) {
		this.storageClass = storageClass;
	}

	IStorageManagementProvider managementProvider;
	public IStorageManagementProvider getManagementProvider() {
		return managementProvider;
	}

	public void setManagementProvider(IStorageManagementProvider managementProvider) {
		this.managementProvider = managementProvider;
	}


	Map<String, Object> configuration;
	public Map<String, Object> getConfiguration() {
		return configuration;
	}

	public void setConfiguration(Map<String, Object> configuration) {
		this.configuration = configuration;
	}


	public NfsVolumeProperties createPersistentVolume(Map<String, String> annotations, UUID uuid, long sizeInBytes) throws Exception {
		return managementProvider.createPersistentVolume(annotations, uuid, sizeInBytes);
	}

	public void removePersistentVolume(Map<String, String> annotations) throws Exception {
		managementProvider.removePersistentVolume(annotations);
	}

	String pvNameFormat;
	public void setpvNameFormat(String namePrefix) {
		this.pvNameFormat = namePrefix;
	}
	public String getpvNameFormat() {
		return pvNameFormat;
	}
}
