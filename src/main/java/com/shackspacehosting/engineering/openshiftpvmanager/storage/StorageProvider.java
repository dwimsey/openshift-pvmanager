package com.shackspacehosting.engineering.openshiftpvmanager.storage;

import com.shackspacehosting.engineering.openshiftpvmanager.storage.providers.IStorageManagementProvider;
import com.shackspacehosting.engineering.openshiftpvmanager.storage.providers.NfsVolumeProperties;

import java.util.Map;
import java.util.UUID;

import static com.shackspacehosting.engineering.openshiftpvmanager.PVClaimManagerService.*;

public class StorageProvider {
	String storageClass;
	public String getStorageClass() {
		return storageClass;
	}

	public void setStorageClass(String storageClass) {
		this.storageClass = storageClass;
	}

	String reclaimPolicy = ANNOTATION_RECLAIM_POLICY_DEFAULT;
	public String getReclaimPolicy() {
		return reclaimPolicy;
	}

	public void setReclaimPolicy(String reclaimPolicy) {
		this.reclaimPolicy = reclaimPolicy;
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

	public String getDefaultPvNameFormat() {
		return getStorageClass() + "-{" + ANNOTATION_PVMANAGER_PVCNAMESPACE + "}-{" + ANNOTATION_PVMANAGER_PVCNAME + "}-{" + ANNOTATION_PVMANAGER_PVTAG + "}";
	}

	String pvNamePrefix = null;
 	public void setPvNameFormat(String namePrefix) {
		this.pvNamePrefix = namePrefix;
	}
	public String getPvNameFormat() {
		return pvNamePrefix;
	}
}
