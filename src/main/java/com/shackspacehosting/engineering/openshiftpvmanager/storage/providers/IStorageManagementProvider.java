package com.shackspacehosting.engineering.openshiftpvmanager.storage.providers;


import java.util.Map;
import java.util.UUID;

public interface IStorageManagementProvider {
	NfsVolumeProperties createPersistentVolume(Map<String, String> annotations, UUID uuid, long sizeInBytes) throws Exception;
	void removePersistentVolume(Map<String, String> annotations) throws Exception;
}
