package com.shackspacehosting.engineering.openshiftpvmanager;

import com.google.common.reflect.TypeToken;
import com.shackspacehosting.engineering.openshiftpvmanager.kubernetes.ObjectNameMapper;
import com.shackspacehosting.engineering.openshiftpvmanager.storage.providers.NfsVolumeProperties;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
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
import org.springframework.security.access.method.P;
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
import java.util.regex.Matcher;

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
										LOG.info("Pending PVC (" + metadata.getNamespace() + ":" + metadata.getName() + ") size: " + size.toPlainString() + " -> " + item.type);

										String volumeName = metadata.getName();
										String namespace = metadata.getNamespace();
										Map<String, String> annotations = metadata.getAnnotations();
										V1LabelSelector selector = spec.getSelector();
										Map<String, String> labels = null;
										if(selector != null) {
											labels = spec.getSelector().getMatchLabels();
										}

										List<String> accessModes = spec.getAccessModes();
										PVCChangeNotification pvcChangeNotification = new PVCChangeNotification(namespace, volumeName, accessModes, labels, annotations, size, status.getPhase(), item.type);
										pvcQueue.add(pvcChangeNotification);
										break;
									case "Bound":
										LOG.trace("Bound PVC (" + metadata.getNamespace() + ":" + metadata.getName() + ")state: " + status.getPhase());
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
						LOG.error("Unhandled crash in Persistent Volume Claim Manager (PVCW): " + e);
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
							LOG.info("pvcChangeNotification dequeued: {}", pvcChangeNotification.toString());
							processPvcChange(client, pvcChangeNotification);
							pvcChangeNotification = pvcQueue.poll();
						}

					} catch(Exception e) {
						LOG.error("Unhandled crash in Persistent Volume Claim Notification Manager (PVCNMW): " + e);
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

								switch(status.getPhase()) {
									case "Released":
//										BigDecimal size = spec.get .getResources().getRequests().get("storage").getNumber();
										String volumeName = metadata.getName();
										String namespace = metadata.getNamespace();
										Map<String, String> annotations = metadata.getAnnotations();
										if(annotations != null && annotations.containsKey("managed-by")) {
											if(annotations.get("managed-by").equals("pvmanager")) {
												V1NFSVolumeSource nfsVolumeProperties = spec.getNfs();
												LOG.info("Deleting NFS provisioned volume: " + nfsVolumeProperties.getServer() + ":" + nfsVolumeProperties.getPath());
												V1DeleteOptions deleteOptions = new V1DeleteOptions();
												deleteOptions.propagationPolicy("Background");
												deleteOptions.setGracePeriodSeconds(0L);
												deleteOptions.setKind(claim.getKind());
												deleteOptions.setApiVersion(claim.getApiVersion());
												V1Status statuss = api.deletePersistentVolume(volumeName, deleteOptions, null, null, null, null);
												LOG.warn("Release Status: " + statuss.toString());
												LOG.warn("Released PV: " + claim.toString());
											}
										}
										break;
									case "Bound":
										LOG.info("Bound PV (" + metadata.getNamespace() + ":" + metadata.getName() + ") state: " + status.getPhase(), ": " + claim.toString());
										break;
									case "Available":
										LOG.info("Available PV (" + metadata.getNamespace() + ":" + metadata.getName() + ") state: " + status.getPhase(), ": " + claim.toString());
										break;
									case "Pending":
										LOG.info("Pending PV (" + metadata.getNamespace() + ":" + metadata.getName() + ") state: " + status.getPhase(), ": " + claim.toString());
										break;
									default:
										LOG.error("Unexpected PV (" + metadata.getNamespace() + ":" + metadata.getName() + ") state: " + status.getPhase(), ": " + claim.toString());
										break;
								}
//System.out.printf("%s : %s%n", item.type, claim.getKind());
							}


						}



					} catch(InterruptedIOException ie) {
						LOG.info("Persistent Volume Watcher interrupted");
						// We get interrupted when destroy() is called, so skip the delay below
						continue;
					} catch(Exception e) {
						LOG.error("Unhandled crash in Persistent Volume Manager (PVW): " + e);
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
					try {
						if(client == null) {
							client = getAuthenticatedApiClient();
						}

						PVChangeNotification pvChangeNotification = pvQueue.poll();
						while(pvChangeNotification != null) {
							LOG.trace("pvChangeNotification dequeued: {}", pvChangeNotification.toString());
							processPvChange(client, pvChangeNotification);
							pvChangeNotification = pvQueue.poll();
						}

					} catch(Exception e) {
						LOG.error("Unhandled crash in Persistent Volume Notifcation Manager (PVNMW): " + e);
						client = null;
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

		pvChangeWatcherServiceThread.start();
		pvChangeNotificationServiceThread.start();
		pvcChangeWatcherServiceThread.start();
		pvcChangeNotificationServiceThread.start();
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
							LOG.error("Exception in something weird internally: {}", e);
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
				// We should trigger the process of cleansing the associated PV
				LOG.error("Delete pvc: " + "----??----" + "( " + pvcChangeNotification.getVolumeName() + ") " + pvcChangeNotification.getNamespace() + ": " + pvcChangeNotification.getStatus() + " !! " + pvcChangeNotification.getChangeType());
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
		if(pvChangeWatcherServiceThread != null) {
			pvChangeWatcherServiceThread.interrupt();
			pvChangeWatcherServiceThread.join();
		}
		if(pvChangeNotificationServiceThread != null) {
			pvChangeNotificationServiceThread.interrupt();
			pvChangeNotificationServiceThread.join();
		}
		if(pvcChangeWatcherServiceThread != null) {
			pvcChangeWatcherServiceThread.interrupt();
			pvcChangeWatcherServiceThread.join();
		}
		if(pvcChangeNotificationServiceThread != null) {
			pvcChangeNotificationServiceThread.interrupt();
			pvcChangeNotificationServiceThread.join();
		}
	}

	private void processPvChange(ApiClient client, PVChangeNotification pvcn) {
		LOG.info(" Event for resource: {}", pvcn.getName());
		LOG.info("       Access Modes: {}", pvcn.getAccessModes());

		Map<String, String> annotations = pvcn.getAnnotations();
		if(annotations != null) {
			String annotationValue = annotations.getOrDefault("managed-by", null);
			if ("pvmanager".equals(annotationValue)) {
				// This application is the owner of this pv, lets see what we need to do with it
			}
		}
	}


	private void createPVForPVC(ApiClient client, PVCChangeNotification pvc) throws IOException {//}, JSchException {
		BigDecimal requestedStorageInBytes = pvc.getRequestedStorage();
		UUID uuid = UUID.randomUUID();

		NfsVolumeProperties persistentVolumeProperties = null;
		try {
			persistentVolumeProperties = storageController.createPersistentVolume(
					ObjectNameMapper.mapKubernetesToPVManagerPVCAnnotations(pvc.getNamespace(), pvc.getVolumeName(), pvc.getAnnotations()),
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
		Map<String, String> annotations = new HashMap<>();

		annotations.put("managed-by", "pvmanager");

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
		//spec.setClaimRef();

		metadata.setName("dynamic-" + pvc.getNamespace() + "-" + uuid.toString());
		metadata.setAnnotations(annotations);

		pv.setSpec(spec);
		pv.setMetadata(metadata);

		try {
			V1PersistentVolume npv = api.createPersistentVolume(pv, null);
			LOG.info("New PV created for PVC " + pvc.getVolumeName() + " -> " + npv.getMetadata().getName() +  ": " + persistentVolumeProperties.toString());
		} catch (Exception e) {
			LOG.error("Exception: " + e.getMessage());
		}
	}
}
