package com.shackspacehosting.engineering.openshiftpvmanager;


import com.shackspacehosting.engineering.openshiftpvmanager.storage.providers.NfsVolumeProperties;

import java.util.Map;
import java.util.UUID;

public interface IStorageController {
	NfsVolumeProperties createPersistentVolume(UUID uuid, long sizeInBytes) throws Exception;
	NfsVolumeProperties createPersistentVolume(Map<String, String> annotations, UUID uuid, long sizeInBytes) throws Exception;
	void removePersistentVolume(Map<String, String> annotations) throws Exception;
}
