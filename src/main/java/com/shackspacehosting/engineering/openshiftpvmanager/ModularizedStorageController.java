package com.shackspacehosting.engineering.openshiftpvmanager;

import com.shackspacehosting.engineering.openshiftpvmanager.storage.StorageControllerConfiguration;
import com.shackspacehosting.engineering.openshiftpvmanager.storage.StorageProvider;
import com.shackspacehosting.engineering.openshiftpvmanager.storage.providers.NfsVolumeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import static com.shackspacehosting.engineering.openshiftpvmanager.PVClaimManagerService.ANNOTATION_STORAGE_CLASS;

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
	public NfsVolumeProperties createPersistentVolume(UUID uuid, long sizeInBytes) throws Exception {
		return createPersistentVolume(null, uuid, sizeInBytes);
	}

	@Override
	public NfsVolumeProperties createPersistentVolume(Map<String, String> annotations, UUID uuid, long sizeInBytes) throws Exception {
		String requestedStorageClass = storageControllerConfiguration.getDefaultStorageClass();
		if(annotations != null && annotations.containsKey(ANNOTATION_STORAGE_CLASS)) {
			requestedStorageClass = annotations.get(ANNOTATION_STORAGE_CLASS);
		}

		NfsVolumeProperties props = null;
		for(StorageProvider provider : storageControllerConfiguration.getStorageProviders()) {
			LOG.trace("Testing storage provider: " + provider.getStorageClass());
			if(requestedStorageClass.compareTo(provider.getStorageClass()) == 0) {
				// This provider serves the storage class requested, attempt to create the volume
				LOG.debug("Trying provider: " + provider.getClass().getName());
				props = provider.createPersistentVolume(annotations, uuid, sizeInBytes);
				if(props != null) {
					props.setNamePrefix(provider.getPvNamePrefix());
					// The provider handled the request, no further processing is needed.
					return props;
				}
			}
		}

		LOG.error("Could not find a storage provider to service the request: " + requestedStorageClass);
		// No providers were able to successfully provide a volume for this request
		return null;
	}

	@Override
	public void removePersistentVolume(Map<String, String> annotations) throws Exception {
		String requestedStorageClass = storageControllerConfiguration.getDefaultStorageClass();
		if(annotations != null && annotations.containsKey(ANNOTATION_STORAGE_CLASS)) {
			requestedStorageClass = annotations.get(ANNOTATION_STORAGE_CLASS);
		}

		NfsVolumeProperties props = null;
		for(StorageProvider provider : storageControllerConfiguration.getStorageProviders()) {
			LOG.trace("Testing storage provider: " + provider.getStorageClass());
			if(requestedStorageClass.compareTo(provider.getStorageClass()) == 0) {
				// This provider serves the storage class requested, attempt to create the volume
				LOG.debug("Trying provider: " + provider.getClass().getName());
				provider.removePersistentVolume(annotations);
				return;
			}
		}

		LOG.error("Could not find a storage provider to service the request: " + requestedStorageClass);
		// No providers were able to successfully provide a volume for this request
	}
}
