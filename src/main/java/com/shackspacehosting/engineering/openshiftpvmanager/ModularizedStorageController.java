package com.shackspacehosting.engineering.openshiftpvmanager;

import com.openshift.restclient.model.volume.property.IPersistentVolumeProperties;
import com.openshift.restclient.utils.MemoryUnit;
import com.shackspacehosting.engineering.openshiftpvmanager.storage.StorageControllerConfiguration;
import com.shackspacehosting.engineering.openshiftpvmanager.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

public class ModularizedStorageController implements IStorageController {
	private static final Logger LOG = LoggerFactory.getLogger(ModularizedStorageController.class);

	private StorageControllerConfiguration storageControllerConfiguration;

	public ModularizedStorageController(String storageConfiguration) throws IOException {
		try {
			storageControllerConfiguration = new StorageControllerConfiguration(storageConfiguration);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			LOG.error("Could not parse configuration file: {}", e);
			throw e;
		}
	}

	@Override
	public IPersistentVolumeProperties createPersistentVolume(UUID uuid, long aLong, MemoryUnit memoryUnit) {
		return null;
	}

	@Override
	public IPersistentVolumeProperties createPersistentVolume(Map<String, String> annotations, UUID uuid, long sizeInUnits, MemoryUnit unitSize) throws Exception {
		String requestedStorageClass = storageControllerConfiguration.getDefaultStorageClass();
		if(annotations.containsKey("storage-class")) {
			requestedStorageClass = annotations.get("storage-class");
		}

		IPersistentVolumeProperties props;
		for(StorageProvider provider : storageControllerConfiguration.getStorageProviders()) {
			if(requestedStorageClass.compareTo(provider.getStorageClass()) == 0) {
				// This provider serves the storage class requested, attempt to create the volume
				props = provider.createPersistentVolume(annotations, uuid, sizeInUnits, unitSize);
				if(props != null) {
					// The provider handled the request, no further processing is needed.
					return props;
				}
			}
		}

		// No providers were able to successfully provide a volume for this request
		return null;
	}
}
