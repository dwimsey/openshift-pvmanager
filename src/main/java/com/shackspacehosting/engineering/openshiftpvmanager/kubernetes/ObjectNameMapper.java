package com.shackspacehosting.engineering.openshiftpvmanager.kubernetes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.util.HashMap;
import java.util.Map;

import static com.shackspacehosting.engineering.openshiftpvmanager.PVClaimManagerService.ANNOTATION_BASE;

public class ObjectNameMapper {
	static private ObjectMapper yamlObjectMapper = null;
	static public ObjectMapper getYamlObjectMapper() {
		if (yamlObjectMapper == null) {
			yamlObjectMapper = new ObjectMapper(new YAMLFactory());
		}
		return yamlObjectMapper;
	}
	static public Map<String, String> mapKubernetesToPVManagerPVCAnnotations(String namespace, String name, Map<String, String> annotationMap) {
		Map<String, String> annotations = new HashMap<String, String>();
		if (namespace != null) {
			annotations.put(ANNOTATION_BASE + "pvc-namespace", namespace);
		}
		if (name != null) {
			annotations.put(ANNOTATION_BASE + "pvc-name", name);
		}
		if(annotationMap != null) {
			for (Map.Entry<String, String> e : annotationMap.entrySet()) {
				if (e.getKey().equals("volume.beta.kubernetes.io/storage-provisioner")) {
					annotations.put("storage-provisioner", e.getValue());
				} else if (e.getKey().equals("volume.beta.kubernetes.io/storage-class")) {
					annotations.put("storage-class", e.getValue());
				} else {
					annotations.put(e.getKey(), e.getValue());
				}
			}
		}
		return annotations;
	}
}
