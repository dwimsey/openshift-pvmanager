package com.shackspacehosting.engineering.openshiftpvmanager;


import java.util.Map;
import java.util.UUID;

public interface IStorageController {
	public Object createPersistentVolume(UUID uuid, long sizeInUnits) throws Exception;
	public Object createPersistentVolume(Map<String, String> annotations, UUID uuid, long sizeInUnits) throws Exception;

}
