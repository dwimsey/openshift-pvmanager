package com.shackspacehosting.engineering.pvmanager.storage;


import com.shackspacehosting.engineering.pvmanager.kubernetes.PVClaimManagerService;
import com.shackspacehosting.engineering.pvmanager.kubernetes.PVClaimManagerService.PvVolumeBlockmode;
import io.kubernetes.client.models.V1PersistentVolumeSpec;

import java.util.Map;
import java.util.UUID;

public interface IStorageManagementProvider {
	V1PersistentVolumeSpec createPersistentVolume(Map<String, String> annotations, long sizeInBytes) throws Exception;
	void removePersistentVolume(Map<String, String> annotations) throws Exception;
}
