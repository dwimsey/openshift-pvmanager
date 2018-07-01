package com.shackspacehosting.engineering.openshiftpvmanager.storage.providers;


import java.util.Map;
import java.util.UUID;

public interface IStorageManagementProvider {
	public NfsVolumeProperties createPersistentVolume(Map<String, String> annotations, UUID uuid, long sizeInUnits/*, MemoryUnit unitSize*/) throws Exception;
}
