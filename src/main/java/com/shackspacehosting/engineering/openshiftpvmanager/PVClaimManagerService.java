package com.shackspacehosting.engineering.openshiftpvmanager;

import com.google.common.reflect.TypeToken;
import com.shackspacehosting.engineering.openshiftpvmanager.kubernetes.ObjectNameMapper;
import com.shackspacehosting.engineering.openshiftpvmanager.storage.providers.NfsVolumeProperties;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.models.*;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.Watch;
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
import java.io.InterruptedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.kubernetes.client.custom.Quantity.Format.BINARY_SI;

@Component
public class PVClaimManagerService implements InitializingBean, DisposableBean {
	private static final Logger LOG = LoggerFactory.getLogger(PVClaimManagerService.class);

	@Value("${kubernetes.service.scheme:https}")
	private String kubernetesServiceScheme;

	@Value("${kubernetes.service.validatessl:true}")
	private Boolean kubernetesServiceValidateSSL;

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

	@Value("${kubernetes.readtimeout:0}")
	private int watcherReadTimeout;

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

	private Queue<PVCChangeNotification> pvcQueue;
	private Queue<PVChangeNotification> pvQueue;

	final public static String ANNOTATION_MANAGED_BY = "managed-by";
	final public static String ANNOTATION_BASE = "us.wimsey.pvmanager/";
	final public static String ANNOTATION_VOLUME_UUID = ANNOTATION_BASE + "volume-uuid";
	final public static String ANNOTATION_PROVIDER_TYPE = ANNOTATION_BASE + "managed-provider";


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

		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					Thread.sleep(3000);
					startSpringServiceManagerThread();
				} catch (InterruptedException e) {
				}

			}
		}).start();
	}

	private boolean beanShouldStop = false;
	Thread pvChangeWatcherServiceThread = null;
	Thread pvChangeNotificationServiceThread = null;
	Thread pvcChangeWatcherServiceThread = null;
	Thread pvcChangeNotificationServiceThread = null;
	private void startSpringServiceManagerThread() {
		pvcChangeWatcherServiceThread = new Thread(new Runnable() {
			@Override
			public void run() {
				while(!beanShouldStop) {
					try {
						ApiClient client = getAuthenticatedApiClient();
						CoreV1Api api = new CoreV1Api(client);

						try(Watch<V1PersistentVolumeClaim> watch = Watch.createWatch(
								client,
								api.listPersistentVolumeClaimForAllNamespacesCall(null, null, null, null,
										0, null, null, 0, Boolean.TRUE, null, null),
								new TypeToken<Watch.Response<V1PersistentVolumeClaim>>(){}.getType())) {

							for (Watch.Response<V1PersistentVolumeClaim> item : watch) {
								V1PersistentVolumeClaim claim = (V1PersistentVolumeClaim)item.object;
								V1PersistentVolumeClaimStatus status = claim.getStatus();
								V1ObjectMeta metadata = claim.getMetadata();
								V1PersistentVolumeClaimSpec spec = claim.getSpec();

								switch(status.getPhase()) {
									case "Pending":
										BigDecimal size = spec.getResources().getRequests().get("storage").getNumber();
										if(LOG.isDebugEnabled()) {
											LOG.debug("Pending PVC (" + metadata.getNamespace() + ":" + metadata.getName() + ") size: " + size.toPlainString() + " -> " + item.type);
										}
										String volumeName = metadata.getName();
										String namespace = metadata.getNamespace();
										Map<String, String> annotations = metadata.getAnnotations();
										V1LabelSelector selector = spec.getSelector();
										Map<String, String> labels = null;
										if(selector != null) {
											labels = spec.getSelector().getMatchLabels();
										}

										List<String> accessModes = spec.getAccessModes();
										PVCChangeNotification pvcChangeNotification = new PVCChangeNotification(namespace, volumeName, accessModes, labels, annotations, size, status.getPhase(), item.type, metadata.getUid());
										pvcQueue.add(pvcChangeNotification);
										break;
									case "Bound":
										LOG.trace("Bound PVC (" + metadata.getNamespace() + ":" + metadata.getName() + ")state: " + status.getPhase());
										break;
									case "Lost":
										LOG.error("LOST PVC (" + metadata.getNamespace() + ":" + metadata.getName() + ")state: " + status.getPhase());
										break;
									default:
										LOG.error("Unexpected PVC (" + metadata.getNamespace() + ":" + metadata.getName() + ") state: " + status.getPhase());
										break;
								}
							}


						}

					} catch(InterruptedIOException ie) {
						LOG.info("Persistent Volume Claim Watcher interrupted");
						// We get interrupted when destroy() is called, so skip the delay below
						continue;
					} catch(Exception e) {
						LOG.error("Unhandled exception in Persistent Volume Claim Manager (PVCW): " + e);
					}

					try {
						Thread.sleep(watcherPollSleepTime);
					} catch (InterruptedException e) {
						LOG.info("Claim Watcher Interrupted Sleep: " + e);
					}
				}
				LOG.info("Persistent Volume Claim Watcher is stopped.");
			}
		});

		pvcChangeNotificationServiceThread = new Thread(new Runnable() {
			@Override
			public void run() {
				ApiClient client = null;
				while(!beanShouldStop) {
					try {
						if(client == null) {
							client = getAuthenticatedApiClient();
						}
						PVCChangeNotification pvcChangeNotification = pvcQueue.poll();
						while(pvcChangeNotification != null) {
							LOG.trace("pvcChangeNotification dequeued: {}", pvcChangeNotification.toString());
							processPvcChange(client, pvcChangeNotification);
							pvcChangeNotification = pvcQueue.poll();
						}

					} catch(Exception e) {
						LOG.error("Unhandled exception in Persistent Volume Claim Notification Manager (PVCNMW): " + e);
						client = null;
					}

					try {
						Thread.sleep(watcherPollSleepTime);
					} catch (InterruptedException e) {
						LOG.trace("Claim Notification Watcher Interrupted Sleep: " + e);
					}
				}
				LOG.info("Persistent Volume Claim Notification Watcher is stopped.");
			}
		});

		pvChangeWatcherServiceThread = new Thread(new Runnable() {
			@Override
			public void run() {
				while(!beanShouldStop) {
					try {
						ApiClient client = getAuthenticatedApiClient();
						CoreV1Api api = new CoreV1Api(client);

						try(Watch<V1PersistentVolume> watch = Watch.createWatch(
								client,
								api.listPersistentVolumeCall(null, null, null, Boolean.TRUE,
										null, null, null, 0, Boolean.TRUE,
										null, null),
								new TypeToken<Watch.Response<V1PersistentVolume>>(){}.getType())) {

							for (Watch.Response<V1PersistentVolume> item : watch) {
								V1PersistentVolume claim = item.object;
								V1PersistentVolumeStatus status = claim.getStatus();
								V1ObjectMeta metadata = claim.getMetadata();
								V1PersistentVolumeSpec spec = claim.getSpec();
								Map<String, String> annotations = metadata.getAnnotations();

								if(annotations != null && annotations.containsKey(ANNOTATION_MANAGED_BY)) {
									if (annotations.get(ANNOTATION_MANAGED_BY).equals("pvmanager")) {
									} else {
										continue;
									}
								} else {
									continue;
								}

								pvQueue.add(new PVChangeNotification(metadata.getNamespace(), metadata.getName(), claim.getKind(), status.getPhase(), spec.getAccessModes(), metadata.getAnnotations(), metadata.getLabels(), new NfsVolumeProperties(spec.getNfs())));
							}


						}



					} catch(InterruptedIOException ie) {
						LOG.info("Persistent Volume Watcher interrupted");
						// We get interrupted when destroy() is called, so skip the delay below
						continue;
					} catch(Exception e) {
						LOG.error("Unhandled exception in Persistent Volume Manager (PVW): " + e);
						e.printStackTrace();
					}

					try {
						Thread.sleep(watcherPollSleepTime);
					} catch (InterruptedException e) {
						LOG.info("Volume Watcher Interrupted Sleep: " + e);
					}
				}
				LOG.info("Persistent Volume Watcher is stopped.");
			}
		});

		pvChangeNotificationServiceThread = new Thread(new Runnable() {
			@Override
			public void run() {
				ApiClient client = null;
				while(!beanShouldStop) {
					PVChangeNotification pvChangeNotification = pvQueue.poll();
					while(pvChangeNotification != null) {
						LOG.trace("pvChangeNotification dequeued: {}", pvChangeNotification.toString());
						try {
							if(client == null) {
								client = getAuthenticatedApiClient();
							}
							processPvChange(client, pvChangeNotification);
						} catch(Exception e) {
							LOG.error("Unhandled exception in Persistent Volume Notifcation Manager (PVNMW): " + e);
							client = null;
							// @TODO Do something with the pvChangeNotification we just dropped on the floor
						}
						pvChangeNotification = pvQueue.poll();
					}
					try {
						Thread.sleep(watcherPollSleepTime);
					} catch (InterruptedException e) {
						LOG.trace("Volume Notification Watcher Interrupted Sleep: " + e);
					}
				}
				LOG.info("Persistent Volume Notification Watcher is stopped.");
			}
		});

		pvChangeNotificationServiceThread.start();
		pvChangeWatcherServiceThread.start();
		pvcChangeNotificationServiceThread.start();
		pvcChangeWatcherServiceThread.start();
	}

	private void processPvcChange(ApiClient client, PVCChangeNotification pvcChangeNotification) {
		switch (pvcChangeNotification.getChangeType().toLowerCase()) {
			case "added":
				switch (pvcChangeNotification.getStatus().toLowerCase()) {
					case "pending":
						try {
							Map<String, String> annotations = pvcChangeNotification.getAnnotations();
							if (annotations != null && annotations.containsKey("volume.beta.kubernetes.io/storage-provisioner")) {
								if (annotations.get("volume.beta.kubernetes.io/storage-provisioner").equals("wimsey.us/pvmanager")) {
									createPVForPVC(client, pvcChangeNotification);
								}
							} else {
								createPVForPVC(client, pvcChangeNotification);
							}
//						} catch (JSchException e) {
//							LOG.error("SSH Exception: {}", e);
						} catch (Exception e) {
							LOG.error("Exception in something weird internally: " + e);
						}
						break;
					case "lost": // don't do anything with this one atm, openshift won't remap to a new available PV so theres no point in creating one
					case "bound": // this PVC is already handled, not sure why we're seeing it
						break;
					default:
						LOG.error("Found pvc: " + "----!!----" + "( " + pvcChangeNotification.getVolumeName() + ") " + pvcChangeNotification.getNamespace() + ": unknown state: " + pvcChangeNotification.getStatus());
				}
				break;

			case "modified":
				// can we do anything here?
				break;
			case "deleted":
				LOG.trace("Delete pvc: " + "----??----" + "( " + pvcChangeNotification.getVolumeName() + ") " + pvcChangeNotification.getNamespace() + ": " + pvcChangeNotification.getStatus() + " !! " + pvcChangeNotification.getChangeType());
				break;
			default:
				// We should trigger the process of cleansing the associated PV
				LOG.error("Found pvc: " + "----??----" + "( " + pvcChangeNotification.getVolumeName() + ") " + pvcChangeNotification.getNamespace() + ": " + pvcChangeNotification.getStatus() + " !! " + pvcChangeNotification.getChangeType());
				break;
		}
	}

	private ApiClient getAuthenticatedApiClient() throws IOException {
		String openShiftPassword;
		if (kubernetesServiceToken.startsWith("/")) {
			// If the password starts with /, it is expected
			// to be a filename pointing to a token.  Basically
			// this means passwords can't start with '/', but so what? --dwimsey

			// we re-read this every time, just in case it changes this way we get new tokens
			// as needed if we get disconnected due to a bad token (or any other reason)
			openShiftPassword = new String(Files.readAllBytes(Paths.get(kubernetesServiceToken)));
		} else {
			openShiftPassword = kubernetesServiceToken;
		}

		String openShiftUrl = kubernetesServiceScheme + "://" + kubernetesServiceHost + ":" + kubernetesServicePort;

		ApiClient client;
		if (kubernetesServiceUsername != null && !kubernetesServiceUsername.isEmpty()) {
			LOG.debug("Using username/password authentication: {}", kubernetesServiceUsername);
			client = Config.fromUserPassword(openShiftUrl, kubernetesServiceUsername, openShiftPassword);
		} else {
			LOG.debug("Using BearerToken: {}", kubernetesServiceToken);
			client = Config.fromToken(openShiftUrl, openShiftPassword);
			client.setVerifyingSsl(kubernetesServiceValidateSSL);
			client.getHttpClient().setReadTimeout(watcherReadTimeout, TimeUnit.MILLISECONDS);
		}
		return client;
	}

	@Override
	public void destroy() throws Exception {
		beanShouldStop = true;
		if(pvcChangeWatcherServiceThread != null) {
			pvcChangeWatcherServiceThread.interrupt();
		}
		if(pvChangeWatcherServiceThread != null) {
			pvChangeWatcherServiceThread.interrupt();
			pvChangeWatcherServiceThread.join();
		}
		if(pvcChangeWatcherServiceThread != null) {
			pvcChangeWatcherServiceThread.join();
		}



		if(pvChangeNotificationServiceThread != null) {
			pvChangeNotificationServiceThread.interrupt();
		}
		if(pvcChangeNotificationServiceThread != null) {
			pvcChangeNotificationServiceThread.interrupt();
			pvcChangeNotificationServiceThread.join();
		}
		if(pvChangeNotificationServiceThread != null) {
			pvChangeNotificationServiceThread.join();
		}
	}

	private void processPvChange(ApiClient client, PVChangeNotification pvcn) throws Exception {
		Map<String, String> annotations = pvcn.getAnnotations();
		if(annotations == null) {
			// No annotations, we can't possibly own this item
			LOG.trace("PV Notification is not for this controller: no annotations");
			return;
		} else {
			String annotationValue = annotations.getOrDefault(ANNOTATION_MANAGED_BY, null);
			if ("pvmanager".equals(annotationValue) == false) {
				// This application is not the owner of this pv, ignore this notification
				LOG.trace("PV Notification is not for this controller: " + ANNOTATION_MANAGED_BY + ": {}", annotationValue);
				return;
			}
		}

		switch(pvcn.getUpdateType()) {
			case "Released":
				// @TODO Clean up nfs share
				removePV(client, pvcn);
				break;
			case "Bound":
				LOG.trace("Bound PV (" + pvcn.getNamespace() + ":" + pvcn.getName() + ") state: " + pvcn.getUpdateType());
				break;
			case "Available":
				LOG.info("Available PV (" + pvcn.getNamespace() + ":" + pvcn.getName() + ") state: " + pvcn.getUpdateType());
				break;
			case "Pending":
				LOG.debug("Pending PV (" + pvcn.getNamespace() + ":" + pvcn.getName() + ") state: " + pvcn.getUpdateType());
				break;
			default:
				LOG.error("Unexpected PV (" + pvcn.getNamespace() + ":" + pvcn.getName() + ") state: " + pvcn.getUpdateType());
				break;
		}
	}


	private void removePV(ApiClient client, PVChangeNotification pvcn) throws Exception {
		String volumeName = pvcn.getName();
		String namespace = pvcn.getNamespace();

		storageController.removePersistentVolume(pvcn.getAnnotations());


		V1DeleteOptions deleteOptions = new V1DeleteOptions();
		CoreV1Api api = new CoreV1Api(client);
		api.deletePersistentVolume(volumeName, deleteOptions, null, null, null, null);
	}

	private void createPVForPVC(ApiClient client, PVCChangeNotification pvc) throws IOException {//}, JSchException {
		BigDecimal requestedStorageInBytes = pvc.getRequestedStorage();
		UUID uuid = UUID.randomUUID();

		Map<String, String> annotations = ObjectNameMapper.mapKubernetesToPVManagerPVCAnnotations(pvc.getNamespace(), pvc.getVolumeName(), pvc.getAnnotations());
		annotations.put(ANNOTATION_MANAGED_BY, "pvmanager");
		annotations.put(ANNOTATION_VOLUME_UUID, uuid.toString());

		NfsVolumeProperties persistentVolumeProperties = null;
		try {
			persistentVolumeProperties = storageController.createPersistentVolume(
					annotations,
					uuid,
					Long.valueOf(requestedStorageInBytes.toPlainString()));
			if (persistentVolumeProperties == null) {
				LOG.error("Persistent volume request could not be fulfilled by any providers.");
				return;
			}
		} catch (Exception e) {
			LOG.error("Unhandled exception attempting to create persistent volume for claim: {}", e);
			return;
		}

		CoreV1Api api = new CoreV1Api(client);

		V1PersistentVolume pv = new V1PersistentVolume();
		V1PersistentVolumeSpec spec = new V1PersistentVolumeSpec();
		List<String> accessModes = new ArrayList<String>();
		Map<String, Quantity> capacity = new HashMap<String, Quantity>();
		V1NFSVolumeSource nfsSource = new V1NFSVolumeSource();
		V1ObjectMeta metadata = new V1ObjectMeta();


		accessModes.add("ReadWriteOnce");

		capacity.put("storage", new Quantity(pvc.getRequestedStorage(), BINARY_SI));

		nfsSource.setServer(persistentVolumeProperties.getNfsHostname());
		nfsSource.setPath(persistentVolumeProperties.getNfsExportPath());
		nfsSource.setReadOnly(persistentVolumeProperties.isReadOnly());

		spec.setAccessModes(accessModes);
		// Delete, Retain, Recycle
		spec.setPersistentVolumeReclaimPolicy("Retain");
		spec.setCapacity(capacity);
		spec.setNfs(nfsSource);

		V1ObjectReference claimRef = new V1ObjectReference();
		claimRef.setNamespace(pvc.getNamespace());
		claimRef.setName(pvc.getVolumeName());
		claimRef.setUid(pvc.getClaimUid());
		spec.setClaimRef(claimRef);

		metadata.setName(persistentVolumeProperties.getNamePrefix() + pvc.getNamespace() + "-" + pvc.getVolumeName() + "-" + uuid.toString().substring(0,7));
		metadata.setAnnotations(annotations);

		pv.setSpec(spec);
		pv.setMetadata(metadata);

		try {
			V1PersistentVolume npv = api.createPersistentVolume(pv, null);
			LOG.info("PV created for PVC " + pvc.getNamespace() + "-" + pvc.getVolumeName() + " -> " + npv.getMetadata().getName() +  " on " + persistentVolumeProperties.getNfsHostname() + ":" + persistentVolumeProperties.getNfsExportPath());
		} catch (Exception e) {
			LOG.error("Exception: " + e.getMessage());
		}
	}
}
