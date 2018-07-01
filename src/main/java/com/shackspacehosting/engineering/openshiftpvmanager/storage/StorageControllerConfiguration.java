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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class StorageControllerConfiguration {
	private static final Logger LOG = LoggerFactory.getLogger(ModularizedStorageController.class);

	private static ObjectMapper mapper = ObjectNameMapper.getYamlObjectMapper();

	public StorageControllerConfiguration(String configuration) throws IOException {
		File configFile = new File(configuration);
		JsonNode node = mapper.readTree(configFile);

		List<StorageProvider> providers = new ArrayList<StorageProvider>(1);

		JsonNode jsonNode;


		jsonNode = node.get("defaultStorageClass");
		if (jsonNode != null) {
			this.defaultStorageClass = jsonNode.asText();
		} else {
			this.defaultStorageClass = "";
		}

		jsonNode = node.get("storageProviders");
		if (jsonNode != null) {
			for (Iterator<JsonNode> itt = jsonNode.elements(); itt.hasNext(); ) {
				JsonNode tt = itt.next();
				JsonNode cfgNode = tt.get("configuration");

				StorageProvider provider = new StorageProvider();
				for (Iterator<Map.Entry<String, JsonNode>> ittt = tt.fields(); ittt.hasNext(); ) {
					Map.Entry<String, JsonNode> ttt = ittt.next();
					String key = ttt.getKey();

					switch(key) {
						case "storageClass":
							provider.setStorageClass(ttt.getValue().asText());
							break;
						case "managementProvider":
							String providerName = ttt.getValue().asText();
							switch (providerName.toUpperCase()) {
								case "NFS":
									JsonNode pName = cfgNode.get("provider");
									String s = pName.asText();
									if (s.equals("zfs")) {
										provider.setManagementProvider(new NFS(cfgNode));
									}
									break;
							}
							break;
					}
				}
				providers.add(provider);
			}
		} else {
			this.defaultStorageClass = "";
		}

		this.setStorageProviders(providers);
	}
	private List<StorageProvider> storageProviders = new ArrayList<StorageProvider>();

	public List<StorageProvider> getStorageProviders() {
		return storageProviders;
	}

	public void setStorageProviders(List<StorageProvider> storageProviders) {
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
