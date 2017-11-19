package com.shackspacehosting.engineering.openshiftpvmanager;

import com.jcraft.jsch.JSchException;
import com.openshift.internal.restclient.model.volume.property.NfsVolumeProperties;
import com.openshift.restclient.ClientBuilder;
import com.openshift.restclient.IClient;
import com.openshift.restclient.IOpenShiftWatchListener;
import com.openshift.restclient.ResourceKind;
import com.openshift.restclient.model.volume.IPersistentVolume;
import com.openshift.restclient.model.volume.property.INfsVolumeProperties;
import com.openshift.restclient.model.volume.property.IPersistentVolumeProperties;
import com.openshift.restclient.utils.MemoryUnit;
import com.shackspacehosting.engineering.openshiftpvmanager.kubernetes.ObjectNameMapper;
import com.shackspacehosting.engineering.openshiftpvmanager.kubernetes.PVCWatchListener;
import com.shackspacehosting.engineering.openshiftpvmanager.kubernetes.PVWatchListener;
import org.apache.ignite.Ignite;
import org.apache.ignite.Ignition;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.configuration.CollectionConfiguration;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.InitializingBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PVClaimWatcherService implements InitializingBean, DisposableBean {
	private static final Logger LOG = LoggerFactory.getLogger(PVClaimWatcherService.class);

	@Value("${kubernetes.service.scheme:https}")
	private String kubernetesServiceScheme;

	@Value("${kubernetes.service.host:kubernetes}")
	private String kubernetesServiceHost;

	@Value("${kubernetes.service.port:443}")
	private String kubernetesServicePort;

	@Value("${kubernetes.service.username:}")
	private String kubernetesServiceUsername;

	@Value("${kubernetes.service.token}")
	private String kubernetesServiceToken;

	@Value("${kubernetes.pollsleepms:500}")
	private int watcherPollSleepTime;

	@Value("${storage.configuration}")
	private String storageConfiguration;

	@Value("${distributed.mode.disabled:false}")
	private Boolean igniteDisabled;

	@Value("${distributed.mode.configuration.file:classpath:ignite.xml}")
	private String igniteConfigurationFile;

	@Value("${distributed.mode.backup.count:0}")
	private Integer igniteReplicationBackup;

	@Value("${distributed.mode.queue.size:0}")
	private Integer igniteQueueSize;

	private ModularizedStorageController storageController = null;

	private IOpenShiftWatchListener pvcWatchListener = null;

	private IOpenShiftWatchListener pvWatchListener = null;

	private Queue<PVCChangeNotification> pvcQueue;
	private Queue<PVChangeNotification> pvQueue;

	@Override
	public void afterPropertiesSet() throws Exception {

		// If we set the replication count to 0, meaning no replicas, then just disable ignite all together.
		// If the replication count is less than 0, require all nodes to be synchronized
		// If the replication count is greater than 0, then require that many backup ignite nodes.  This can be a problem if the cluster contains too few pods running
		if (!igniteDisabled) {
			Ignite ignite = Ignition.start(igniteConfigurationFile);
			CollectionConfiguration cfg = new CollectionConfiguration();
			if(igniteReplicationBackup > 0) {
				cfg.setCacheMode(CacheMode.PARTITIONED);
				cfg.setBackups(igniteReplicationBackup);
			} else {
				cfg.setCacheMode(CacheMode.REPLICATED);
			}
			pvcQueue = ignite.queue("pvcEventQueue", igniteQueueSize, cfg);
			pvQueue = ignite.queue("pvEventQueue", igniteQueueSize, cfg);
		} else {
			pvcQueue = new LinkedBlockingQueue<>();
			pvQueue = new LinkedBlockingQueue<>();
		}

		storageController = new ModularizedStorageController(storageConfiguration);
		startSpringServiceManagerThread();
	}

	public void pvcListenerDisconnected(PVCWatchListener listener) {
		LOG.info("Persistent volume claim listener disconnected");
		this.pvcWatchListener = null;
	}

	public void pvcListenerError(PVCWatchListener listener, Throwable t) {
		LOG.error("Persistent volume claim listener error: {}", t);
		this.pvcWatchListener = null;
	}

	public void pvListenerDisconnected(PVWatchListener listener) {
		LOG.info("Persistent volume listener disconnected");
		this.pvWatchListener = null;
	}

	public void pvListenerError(PVWatchListener listener, Throwable t) {
		LOG.error("Persistent volume listener error: {}", t);
		this.pvWatchListener = null;
	}

	private boolean beanShouldStop = false;
	Thread serviceThread = null;
	private void startSpringServiceManagerThread() {
		serviceThread = new Thread(new Runnable() {
			@Override
			public void run() {
				while(!beanShouldStop) {
					try {
						String openShiftPassword;
						if (kubernetesServiceToken.startsWith("/")) {
							// If the password starts with /, it is expected
							// to be a filename pointing to a token.  Basically
							// this means passwords can't start with '/', but so what? --dwimsey
							try {
								// we re-read this every time, just in case it changes this way we get new tokens
								// as needed if we get disconnected due to a bad token (or any other reason)
								openShiftPassword = new String(Files.readAllBytes(Paths.get(kubernetesServiceToken)));
							} catch(Exception e) {
								LOG.error("Could not read kubernetes token file: " + kubernetesServiceToken + ": " + e.getMessage());
								throw e;
							}
						} else {
							openShiftPassword = kubernetesServiceToken;
						}
						String openShiftUrl = kubernetesServiceScheme + "://" + kubernetesServiceHost + ":" + kubernetesServicePort;

						IClient client;

						if (kubernetesServiceUsername != null && !kubernetesServiceUsername.isEmpty()) {
							client = new ClientBuilder(openShiftUrl)
									.withUserName(kubernetesServiceUsername)
									.withPassword(openShiftPassword)
									.build();
						} else {
							client = new ClientBuilder(openShiftUrl).usingToken(kubernetesServiceToken).build();
						}
						pvcWatchListener = new PVCWatchListener(PVClaimWatcherService.this, pvcQueue, storageController);
						LOG.info("Starting to watch for persistent volume claims");
						client.watch(pvcWatchListener, ResourceKind.PVC);

						pvWatchListener = new PVWatchListener(PVClaimWatcherService.this, pvQueue);
						LOG.info("Starting to watch for persistent volumes");
						client.watch(pvWatchListener, ResourceKind.PERSISTENT_VOLUME);

						while(!beanShouldStop) {
							if (pvcWatchListener == null) {
								pvcWatchListener = new PVCWatchListener(PVClaimWatcherService.this, pvcQueue, storageController);
								LOG.info("Restarting to watch for persistent volume claims");
								client.watch(pvcWatchListener, ResourceKind.PVC);
							}

							if (pvWatchListener == null) {
								pvWatchListener = new PVWatchListener(PVClaimWatcherService.this, pvQueue);
								LOG.info("Restarting to watch for persistent volumes");
								client.watch(pvWatchListener, ResourceKind.PERSISTENT_VOLUME);
							}

							PVCChangeNotification pvcChangeNotification = pvcQueue.poll();
							while(pvcChangeNotification != null) {
								//LOG.error(pvcChangeNotification.toString());
								processNewPvc(client, pvcChangeNotification);
								pvcChangeNotification = pvcQueue.poll();
							}

							PVChangeNotification pvChangeNotification = pvQueue.poll();
							while(pvChangeNotification != null) {
								//LOG.error(pvcChangeNotification.toString());
								processPvChange(client, pvChangeNotification);
								pvChangeNotification = pvQueue.poll();
							}

							try {
								Thread.sleep(watcherPollSleepTime);
							} catch (InterruptedException e) {
								LOG.error("Interrupted Sleep: {}", e);
							}
						}

					} catch(Exception e) {
						LOG.error("Unhandled crash in Persistent Volume Manager: {}", e);
					}
				}
				LOG.info("Persistent Volume Manager is stopped.");
			}
		});
		serviceThread.start();
	}

	@Override
	public void destroy() throws Exception {
		beanShouldStop = true;
		serviceThread.join();
	}

	private void processPvChange(IClient client, PVChangeNotification pvcn) {
		LOG.info(" Event for resource: {}", pvcn.getName());
		LOG.info("       Access Modes: {}", pvcn.getAccessModes());

		Map<String, String> annotations = pvcn.getAnnotations();
		String annotationValue = annotations.getOrDefault("managed-by", null);
		if ("pvmanager".equals(annotationValue)) {
			// This application is the owner of this pv, lets see what we need to do with it
		}
	}

	private void processNewPvc(IClient client, PVCChangeNotification pvccn) {
		switch (pvccn.getUpdateType().toLowerCase()) {
			case "added":
				switch (pvccn.getStatus().toLowerCase()) {
					case "pending":
						try {
							Map<String, String> annotations = pvccn.getAnnotations();
							if (annotations.containsKey("volume.beta.kubernetes.io/storage-provisioner")) {
								if (annotations.get("volume.beta.kubernetes.io/storage-provisioner").equals("wimsey.us/pvmanager")) {
									createPVForPVC(client, pvccn);
								}
							} else {
								createPVForPVC(client, pvccn);
							}
						} catch (JSchException e) {
							LOG.error("SSH Exception: {}", e);
						} catch (Exception e) {
							LOG.error("Exception in something weird internally: {}", e);
						}
						break;
					case "lost": // don't do anything with this one atm, openshift won't remap to a new available PV so theres no point in creating one
					case "bound": // this PVC is already handled, not sure why we're seeing it
						break;
					default:
						LOG.error("Found pvc: " + pvccn.getName() + "( " + pvccn.getVolumeName() + ") " + pvccn.getNamespace() + ": unknown state: " + pvccn.getStatus());
				}
				break;

			case "modified":
				// can we do anything here?
				break;
			case "deleted":
				// We should trigger the process of cleansing the associated PV
				break;
		}
	}




	Pattern storageStringRegexPattern = Pattern.compile("([0-9]+)([A-Za-z]+)");
	private void createPVForPVC(IClient client, PVCChangeNotification pvc) throws IOException, JSchException {
		String requestedStorageString = pvc.getRequestedStorage();
		Matcher m = storageStringRegexPattern.matcher(requestedStorageString);
		boolean b = m.matches();
		if (!b) {
			throw new InvalidPropertiesFormatException("Could not parse requested storage string: " + requestedStorageString);
		}

		String sSize = m.group(1);
		String mUnit = m.group(2);

		UUID uuid = UUID.randomUUID();



		IPersistentVolumeProperties persistentVolumeProperties = null;
		try {
			persistentVolumeProperties = storageController.createPersistentVolume(ObjectNameMapper.mapKubernetesToPVManagerPVCAnnotations(pvc.getNamespace(), pvc.getName(), pvc.getAnnotations()), uuid, Long.valueOf(sSize), MemoryUnit.valueOf(mUnit));
			if (persistentVolumeProperties == null) {
				LOG.error("Persistent volume request could not be fulfilled by any providers.");
				return;
			}
		} catch (Exception e) {
			LOG.error("Unhandled exception attempting to create persistent volume for claim: {}", e);
			return;
		}

		//    Create the pv to NFS mapping
		IPersistentVolume persistentVolume = (IPersistentVolume)client.getResourceFactory().stub(ResourceKind.PERSISTENT_VOLUME, "dynamic-" + ((NfsVolumeProperties)persistentVolumeProperties).getServer() + "-" + uuid.toString());
		Set<String> accessModesSet = pvc.getAccessModes();
		String[] accessModes = new String[accessModesSet.size()];
		accessModesSet.toArray(accessModes);
		persistentVolume.setAccessModes(accessModes);
		persistentVolume.setReclaimPolicy("Retain");
		persistentVolume.setCapacity(Long.valueOf(sSize), MemoryUnit.valueOf(mUnit));
		persistentVolume.setPersistentVolumeProperties(persistentVolumeProperties);
		persistentVolume.setAnnotation("managed-by", "pvmanager");
		Map<String, String> pvcAnnotations = pvc.getAnnotations();
		for(Map.Entry<String,String> annotation : pvcAnnotations.entrySet()) {
			persistentVolume.setAnnotation(annotation.getKey(), annotation.getValue());
		}
		try {
			persistentVolume = client.create(persistentVolume);
			LOG.info("New PV created for PVC " + pvc.getName() + " -> " + persistentVolume.getName() +  ": " + persistentVolumeProperties.toString());
		} catch (Exception e) {
			LOG.error("Exception: " + e.getMessage());
		}
	}
}
