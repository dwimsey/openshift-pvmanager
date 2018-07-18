package com.shackspacehosting.engineering.openshiftpvmanager;

import com.shackspacehosting.engineering.openshiftpvmanager.storage.StorageControllerConfiguration;
import com.shackspacehosting.engineering.openshiftpvmanager.storage.StorageProvider;
import com.shackspacehosting.engineering.openshiftpvmanager.storage.providers.NfsVolumeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.shackspacehosting.engineering.openshiftpvmanager.PVClaimManagerService.ANNOTATION_PVMANAGER_PVTAG;
import static com.shackspacehosting.engineering.openshiftpvmanager.PVClaimManagerService.ANNOTATION_PVMANAGER_RECLAIM_POLICY;
import static com.shackspacehosting.engineering.openshiftpvmanager.PVClaimManagerService.ANNOTATION_RECLAIM_POLICY;
import static com.shackspacehosting.engineering.openshiftpvmanager.PVClaimManagerService.ANNOTATION_RECLAIM_POLICY_DELETE;
import static com.shackspacehosting.engineering.openshiftpvmanager.PVClaimManagerService.ANNOTATION_RECLAIM_POLICY_RECYCLE;
import static com.shackspacehosting.engineering.openshiftpvmanager.PVClaimManagerService.ANNOTATION_RECLAIM_POLICY_RETAIN;
import static com.shackspacehosting.engineering.openshiftpvmanager.PVClaimManagerService.ANNOTATION_STORAGE_CLASS;
import static com.shackspacehosting.engineering.openshiftpvmanager.storage.providers.ZfsOverNfs.ANNOTATION_PVMANAGER_PVREF;

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

	Pattern pattern = Pattern.compile("\\{(.+?)\\}");
	private String replaceTokensInString(Map<String,String> replacements, String text) {
		Matcher matcher = pattern.matcher(text);

		StringBuilder builder = new StringBuilder();
		int i = 0;
		while (matcher.find()) {
			String replacement = replacements.get(matcher.group(1));
			builder.append(text.substring(i, matcher.start()));
			if (replacement == null)
				builder.append(matcher.group(0));
			else
				builder.append(replacement);
			i = matcher.end();
		}
		builder.append(text.substring(i, text.length()));
		return builder.toString();
	}

	@Override
	public NfsVolumeProperties createPersistentVolume(Map<String, String> annotations, UUID uuid, long sizeInBytes) throws Exception {
		String requestedStorageClass = storageControllerConfiguration.getDefaultStorageClass();
		if(annotations != null) {
			if(annotations.containsKey(ANNOTATION_STORAGE_CLASS)) {
				requestedStorageClass = annotations.get(ANNOTATION_STORAGE_CLASS);
			}
		} else {
			annotations = new HashMap<>();
		}


		final StorageProvider provider = storageControllerConfiguration.getStorageProviders().get(requestedStorageClass);
		try {
			annotations.put(ANNOTATION_PVMANAGER_PVTAG, uuid.toString().substring(0,7));
			String pvName = replaceTokensInString(annotations, provider.getPvNameFormat());
			annotations.put(ANNOTATION_PVMANAGER_PVREF, pvName);

			final NfsVolumeProperties props = provider.createPersistentVolume(annotations, uuid, sizeInBytes);
			if(props != null) {
				String reclaimPolicy = provider.getReclaimPolicy();
				if(annotations.containsKey(ANNOTATION_RECLAIM_POLICY)) {
					switch(reclaimPolicy.toLowerCase()) {
						case "delete":
							reclaimPolicy = ANNOTATION_RECLAIM_POLICY_DELETE;
							break;
						case "retain":
							reclaimPolicy = ANNOTATION_RECLAIM_POLICY_RETAIN;
							break;
						case "recycle":
							reclaimPolicy = ANNOTATION_RECLAIM_POLICY_RECYCLE;
							break;
						default:
							LOG.warn("Unexpected " + ANNOTATION_RECLAIM_POLICY + " annotation value: " + reclaimPolicy);
							reclaimPolicy = provider.getReclaimPolicy();
							break;
					}
				} else {
					reclaimPolicy = provider.getReclaimPolicy();
				}
				props.setProviderReclaimPolicy(reclaimPolicy);
				props.setPVName(pvName);
				// The provider handled the request, no further processing is needed.
				return props;
			}
		} finally {
			annotations.remove(ANNOTATION_PVMANAGER_PVTAG);
			annotations.remove(ANNOTATION_PVMANAGER_PVREF);
			annotations.remove(ANNOTATION_PVMANAGER_RECLAIM_POLICY);
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
		StorageProvider provider = storageControllerConfiguration.getStorageProviders().get(requestedStorageClass);
		if(provider == null) {
			LOG.error("Could not find a storage provider to service the cleanup request: " + requestedStorageClass);
		} else {
			provider.removePersistentVolume(annotations);
		}
	}
}
