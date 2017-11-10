package com.shackspacehosting.engineering.openshiftpvmanager.storage;

import com.openshift.restclient.model.volume.property.IPersistentVolumeProperties;
import com.openshift.restclient.utils.MemoryUnit;
import com.shackspacehosting.engineering.openshiftpvmanager.storage.providers.IStorageManagementProvider;

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


	public IPersistentVolumeProperties createPersistentVolume(Map<String, String> annotations, UUID uuid, long sizeInUnits, MemoryUnit unitSize) throws Exception {
		return managementProvider.createPersistentVolume(annotations, uuid, sizeInUnits, unitSize);
	}
}
