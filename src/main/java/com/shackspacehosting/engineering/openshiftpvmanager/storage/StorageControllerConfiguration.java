package com.shackspacehosting.engineering.openshiftpvmanager.storage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shackspacehosting.engineering.openshiftpvmanager.ModularizedStorageController;
import com.shackspacehosting.engineering.openshiftpvmanager.kubernetes.ObjectNameMapper;
import com.shackspacehosting.engineering.openshiftpvmanager.storage.providers.NFS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;

@JsonIgnoreProperties(ignoreUnknown = true)
public class StorageControllerConfiguration {
	private static final Logger LOG = LoggerFactory.getLogger(ModularizedStorageController.class);

	private static ObjectMapper mapper = ObjectNameMapper.getYamlObjectMapper();

	public StorageControllerConfiguration(String configuration) throws IOException {
		File configFile = new File(configuration);
		JsonNode node = mapper.readTree(configFile);

		Map<String, StorageProvider> providers = new HashMap<String, StorageProvider>(1);

		JsonNode jsonNode;


		jsonNode = node.get("defaultStorageClass");
		if (jsonNode != null) {
			this.defaultStorageClass = jsonNode.asText();
		} else {
			this.defaultStorageClass = "";
		}

		jsonNode = node.get("storageProviders");
		if (jsonNode != null) {
			for (Iterator<JsonNode> storageProviderNode = jsonNode.elements(); storageProviderNode.hasNext(); ) {
				JsonNode storageProviderConfigurationNode = storageProviderNode.next();
				JsonNode cfgNode = storageProviderConfigurationNode.get("configuration");

				StorageProvider provider = new StorageProvider();
				if(!storageProviderConfigurationNode.has("storageClass")) {
					LOG.error("Storage provider configuration does not contain as storageClass");
					continue;
				}
				String storageClass = storageProviderConfigurationNode.get("storageClass").asText();
				if(storageClass.isEmpty()) {
					LOG.error("Storage provider configuration storageClass is empty");
					continue;
				}
				provider.setStorageClass(storageClass);


				String pvNameFormat;
				if(storageProviderConfigurationNode.has("pvNameFormat")) {
					pvNameFormat = storageProviderConfigurationNode.get("pvNameFormat").asText();
				} else {
					pvNameFormat = provider.getDefaultPvNameFormat();
				}
				provider.setPvNameFormat(pvNameFormat);

				String providerName = storageProviderConfigurationNode.get("managementProvider").asText();
				switch (providerName.toUpperCase()) {
					case "NFS":
						JsonNode pName = cfgNode.get("provider");
						String s = pName.asText();
						if (s.equals("zfs")) {
							NFS nfs;
							try {
								nfs = new NFS(provider, cfgNode);
							} catch (IllegalArgumentException iae){
								throw new RuntimeException("NFS configuration for storage class '" + storageClass + "' has one or more unrecoverable errors.", iae);
							}
							provider.setManagementProvider(nfs);
						}
						break;
				}
				providers.put(provider.getStorageClass(), provider);
			}
		} else {
			this.defaultStorageClass = "";
		}

		this.setStorageProviders(providers);
	}
	private Map<String, StorageProvider> storageProviders = new HashMap<String, StorageProvider>();

	public Map<String, StorageProvider> getStorageProviders() {
		return storageProviders;
	}

	public void setStorageProviders(Map<String, StorageProvider> storageProviders) {
		this.storageProviders = storageProviders;
	}

	private String defaultStorageClass;
	public String getDefaultStorageClass() {
		return defaultStorageClass;
	}

	public void setDefaultStorageClass(String defaultStorageClass) {
		this.defaultStorageClass = defaultStorageClass;
	}
}
