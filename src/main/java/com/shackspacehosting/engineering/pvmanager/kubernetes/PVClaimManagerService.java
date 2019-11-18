package com.shackspacehosting.engineering.pvmanager.kubernetes;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shackspacehosting.engineering.pvmanager.storage.StorageControllerConfiguration;
import com.shackspacehosting.engineering.pvmanager.storage.StorageProvider;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.apis.CoreV1Api;
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
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.shackspacehosting.engineering.pvmanager.storage.providers.ZfsCliStorageProvider.ANNOTATION_PVMANAGER_PVREF;

@Component
public class PVClaimManagerService implements InitializingBean, DisposableBean {
	private static final Logger LOG = LoggerFactory.getLogger(PVClaimManagerService.class);

	final public static String ANNOTATION_KUBERNETES_STORAGE_PROVISIONER = "volume.beta.kubernetes.io/storage-provisioner";
	final public static String ANNOTATION_KUBERNETES_STORAGE_CLASS = "volume.beta.kubernetes.io/storage-class";

	final public static String ANNOTATION_MANAGED_BY = "managed-by";
	final public static String ANNOTATION_STORAGE_PROVISIONER_NAME = "pvmanager.wimsey.us";

	final public static String ANNOTATION_BASE = ANNOTATION_STORAGE_PROVISIONER_NAME + "/";
	final public static String ANNOTATION_VOLUME_UUID = ANNOTATION_BASE + "volume-uuid";
	final public static String ANNOTATION_PROVIDER_TYPE = ANNOTATION_BASE + "managed-provider";
	final public static String ANNOTATION_CLONEFROM = ANNOTATION_BASE + "clone-from";
	final public static String ANNOTATION_CLONEREF = ANNOTATION_BASE + "clone-ref";
	final public static String ANNOTATION_CLONESNAPSHOT = ANNOTATION_BASE + "clone-snapshot";
	final public static String ANNOTATION_BLOCKSIZE = ANNOTATION_BASE + "blocksize";
	final public static String ANNOTATION_CHECKSUM_MODE = ANNOTATION_BASE + "checksum";
	final public static String ANNOTATION_COMPRESSION_MODE = ANNOTATION_BASE + "compression";
	final public static String ANNOTATION_ATIME = ANNOTATION_BASE + "atime";
	final public static String ANNOTATION_EXEC = ANNOTATION_BASE + "exec";
	final public static String ANNOTATION_SETUID = ANNOTATION_BASE + "setuid";
	final public static String ANNOTATION_RECLAIM_POLICY = ANNOTATION_BASE + "reclaim-policy";
	final public static String ANNOTATION_PVMANAGER_RECLAIM_POLICY = "pvmanager:reclaim-policy";
	final public static String ANNOTATION_RECLAIM_POLICY_RECYCLE = "Recycle";
	final public static String ANNOTATION_RECLAIM_POLICY_RETAIN = "Retain";
	final public static String ANNOTATION_RECLAIM_POLICY_DELETE = "Delete";
	final public static String ANNOTATION_RECLAIM_POLICY_DEFAULT = ANNOTATION_RECLAIM_POLICY_DELETE;

	final public static String ANNOTATION_LOGBIAS = ANNOTATION_BASE + "logbias";
	final public static String ANNOTATION_SNAPDIR = ANNOTATION_BASE + "snapdir";
	final public static String ANNOTATION_SYNC = ANNOTATION_BASE + "sync";
	final public static String ANNOTATION_CASESENSITIVE = ANNOTATION_BASE + "casesensitive";

	final public static String ANNOTATION_STORAGE_PROVISIONER = "storage-provisioner";
	final public static String ANNOTATION_STORAGE_CLASS = "storage-class";
	final public static String ANNOTATION_STORAGE_VOLUMEMODE = ANNOTATION_BASE + "volume-mode";
	final public static String ANNOTATION_STORAGE_MOUNTPROTOCOL = ANNOTATION_BASE + "mount-protocol";

	final public static String ANNOTATION_PVMANAGER_PVCNAMESPACE = ANNOTATION_BASE + "pvc-namespace";
	final public static String ANNOTATION_PVMANAGER_PVCNAME = ANNOTATION_BASE + "pvc-name";
	final public static String ANNOTATION_PVMANAGER_PVTAG = "PVMANAGER-PV-TAG";
	final public static String ANNOTATION_PVMANAGER_RELEASED_TIMESTAMP = ANNOTATION_BASE + "released-at";

	@Value("${kubernetes.client.debugging:false}")
	private Boolean kubernetesClientDebugging;

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

	private Queue<PVCChangeNotification> pvcQueue;
	private Queue<PVChangeNotification> pvQueue;

	private StorageControllerConfiguration storageControllerConfiguration;

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

		try {
			storageControllerConfiguration = new StorageControllerConfiguration(storageConfiguration);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			LOG.error("Could not parse storage configuration file: " + storageConfiguration + "{}", e);
			throw e;
		}

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
								V1PersistentVolumeClaim claim = item.object;
								V1PersistentVolumeClaimStatus status = claim.getStatus();
								V1ObjectMeta metadata = claim.getMetadata();
								V1PersistentVolumeClaimSpec spec = claim.getSpec();

								switch(status.getPhase()) {
									case "Pending":
										BigDecimal size = spec.getResources().getRequests().get("storage").getNumber();
										if(LOG.isDebugEnabled()) {
											LOG.debug("Pending PVC (" + metadata.getNamespace() + ":" + metadata.getName() + ") size: " + size.toPlainString() + " -> " + item.type);
										}

										//metadata.getLabels();
										//metadata.getUid();
										String volumeName = metadata.getName();
										String namespace = metadata.getNamespace();
										Map<String, String> annotations = metadata.getAnnotations();
										Map<String, String> labels = metadata.getLabels();
										Map<String, String> matchedLabels = null;
										V1LabelSelector selector = spec.getSelector();
										if(selector != null) {
											matchedLabels = spec.getSelector().getMatchLabels();
										}

										PVCChangeNotification pvcChangeNotification = new PVCChangeNotification(namespace, volumeName, spec, labels, annotations, size, status.getPhase(), item.type, metadata.getUid());
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
							client.setDebugging(kubernetesClientDebugging == null ? false : kubernetesClientDebugging.booleanValue());
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
									if (!annotations.get(ANNOTATION_MANAGED_BY).equals(ANNOTATION_STORAGE_PROVISIONER_NAME)) {
										continue;
									}
								} else {
									continue;
								}

								pvQueue.add(new PVChangeNotification(metadata.getName(), claim.getKind(), item.type, status.getPhase(), status.getMessage(), status.getReason(), spec.getAccessModes(), metadata.getAnnotations(), metadata.getLabels(), spec, null));
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
							LOG.error("Unhandled exception in Persistent Volume Notifcation Manager (PVNMW)", e);
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
							if (annotations != null && annotations.containsKey(ANNOTATION_KUBERNETES_STORAGE_PROVISIONER)) {
								if (annotations.get(ANNOTATION_KUBERNETES_STORAGE_PROVISIONER).equals(ANNOTATION_STORAGE_PROVISIONER_NAME)) {
									StorageProvider storageProvider = storageControllerConfiguration.getStorageProviders().get(getStorageClassFromAnnotations(annotations));
									if(storageProvider == null) {
										LOG.error("Add pvc (Failed: no storage class for specified provisioner): ( " + pvcChangeNotification.getVolumeName() + ") " + pvcChangeNotification.getNamespace() + ": " + pvcChangeNotification.getStatus() + " !! " + pvcChangeNotification.getChangeType());
										throw new NotImplementedException();
									}
									processPersistentVolumeClaimCreateRequest(client, pvcChangeNotification);
								} else {
									LOG.trace("Add pvc (Ignored by request): ( " + pvcChangeNotification.getVolumeName() + ") " + pvcChangeNotification.getNamespace() + ": " + pvcChangeNotification.getStatus() + " !! " + pvcChangeNotification.getChangeType());
								}
							} else {
								StorageProvider storageProvider = storageControllerConfiguration.getStorageProviders().get(getStorageClassFromAnnotations(annotations));
								if(storageProvider == null) {
									LOG.trace("Add pvc (Ignored: no storage class): ( " + pvcChangeNotification.getVolumeName() + ") " + pvcChangeNotification.getNamespace() + ": " + pvcChangeNotification.getStatus() + " !! " + pvcChangeNotification.getChangeType());
								} else {
									processPersistentVolumeClaimCreateRequest(client, pvcChangeNotification);
								}
							}
						} catch (Exception e) {
							LOG.error("Exception processing pending claim", e);
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
			if (ANNOTATION_STORAGE_PROVISIONER_NAME.equals(annotationValue) == false) {
				// This application is not the owner of this pv, ignore this notification
				LOG.trace("PV Notification is not for this controller: " + ANNOTATION_MANAGED_BY + ": {}", annotationValue);
				return;
			}
		}

		if(pvcn.getChangeType().equals("DELETED")) {
			// If the pvcn is being deleted, release any resources associated with it from our domain
			storageControllerConfiguration.getStorageProviders().get(getStorageClassFromAnnotations(annotations)).removePersistentVolume(annotations);
		} else {
			switch (pvcn.getPvState()) {
				case "Failed":
					LOG.error("State change for failed PV (" + pvcn.getName() + "): " + pvcn.getPvStateMessage());
					break;
				case "Released":
					StorageProvider storageProvider = storageControllerConfiguration.getStorageProviders().get(getStorageClassFromAnnotations(annotations));

					// Does the PV have a reclaim policy set
					String reclaimPolicy = annotations.get(ANNOTATION_RECLAIM_POLICY);
					if(reclaimPolicy == null) {
						// none specified, use the default for this provider.
						reclaimPolicy = storageProvider.getReclaimPolicy();
					}

					// recycle is not supported because there are too many annotations that are not compared
					// so just delete this and we'll let it create a new one (since this is fast with zfs) when we
					// need it
					if(ANNOTATION_RECLAIM_POLICY_RECYCLE.equalsIgnoreCase(reclaimPolicy)) {
						// Really we shouldn't do this for this reclaim policy, the pv is supposed to be reused, but we're doing to delete it
						// anyway because ZFS recreates filesystems instantantly and recycling is hard with all the options available
						deletePersistentVolume(client, pvcn);
						storageProvider.removePersistentVolume(annotations);
					} else if(ANNOTATION_RECLAIM_POLICY_RETAIN.equalsIgnoreCase(reclaimPolicy)) {
						// this allows us to have some sort of after-the-fact cleanup to help deal with volumes of critical data
						// another process will deal with these items later
						// @TODO Add a timestamp to these persistent volumes so they can be scavanged after some period of time
						CoreV1Api api = new CoreV1Api(client);
						V1PersistentVolume persistentVolume = api.readPersistentVolume(pvcn.getName(), null, Boolean.TRUE, Boolean.FALSE);
						if(persistentVolume != null) {
							Map<String, String> pvAnnotations = persistentVolume.getMetadata().getAnnotations();
							if(pvAnnotations != null) {

								if(!pvAnnotations.containsKey(ANNOTATION_PVMANAGER_RELEASED_TIMESTAMP)) {
									Long releasedTimestamp = OffsetDateTime.now().toEpochSecond();
									LOG.info("Marking released persistent volume with timestamp: " + pvcn.getName() + ": " + releasedTimestamp.toString());
									// If there isn't a released timestamp already, add one.  This way we only add a timestamp, not modify an existing
									String patchJson = "{\"op\": \"add\", \"path\": \"/metadata/annotations/" + ANNOTATION_PVMANAGER_RELEASED_TIMESTAMP.replace("/", "~1") + "\", \"value\": \"" + releasedTimestamp.toString() + "\"}";
									ArrayList<JsonObject> arr = new ArrayList<>();
									arr.add(((JsonElement) (new Gson()).fromJson(patchJson, JsonElement.class)).getAsJsonObject());
									api.patchPersistentVolume(pvcn.getName(), arr, null);
								}
							}
						}
					} else if(ANNOTATION_RECLAIM_POLICY_DELETE.equalsIgnoreCase(reclaimPolicy)) {
						// do nothing here, deletion is coming soon enough when kubernetes calls for the deletion of the PersistentVolume itself
						deletePersistentVolume(client, pvcn);
						storageProvider.removePersistentVolume(annotations);
					} else {
						LOG.warn("Released PV unexpected reclaim policy (" + pvcn.getName() + "): " + reclaimPolicy);
					}
					break;
				case "Bound":
					LOG.trace("Bound PV (" + pvcn.getName() + "): " + pvcn.getPvState());
					break;
				case "Available":
					LOG.info("Available PV (" + pvcn.getName() + "): " + pvcn.getPvState());
					break;
				case "Pending":
					LOG.debug("Pending PV (" + pvcn.getName() + "): " + pvcn.getPvState());
					break;
				default:
					LOG.error("Unexpected PV state (" + ":" + pvcn.getName() + "): " + pvcn.getPvState());
					break;
			}
		}
	}

	private void deletePersistentVolume(ApiClient client, PVChangeNotification pvcn) throws Exception {
		String volumeName = pvcn.getName();
		V1DeleteOptions deleteOptions = new V1DeleteOptions();
		CoreV1Api api = new CoreV1Api(client);
		api.deletePersistentVolume(volumeName, deleteOptions, null, null, null, null);
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

	public String getStorageClassFromAnnotations(Map<String, String> annotations) {
		String requestedStorageClass = storageControllerConfiguration.getDefaultStorageClass();
		if(annotations != null) {
			if(annotations.containsKey(ANNOTATION_STORAGE_CLASS)) {
				requestedStorageClass = annotations.get(ANNOTATION_STORAGE_CLASS);
			}
		}
		return requestedStorageClass;
	}

	/**
	 * This method processes a PVCChangedNotification event.  The following steps are taken:
	 * 1. Parse common annotations for commands and configuration options
	 * 2. Generate common annotations for PVC
	 * 2. Check selectors to see if they exist, if so do they match the labels provided for any of our storage drivers
	 * 2.
	 * @param client
	 * @param pvc
	 * @return
	 * @throws Exception
	 */
	private boolean processPersistentVolumeClaimCreateRequest(ApiClient client, PVCChangeNotification pvc) throws Exception {
		CoreV1Api api = new CoreV1Api(client);
		Map<String, String> annotations = ObjectNameMapper.mapKubernetesToPVManagerPVCAnnotations(pvc.getNamespace(), pvc.getVolumeName(), pvc.getAnnotations());

		BigDecimal requestedStorageInBytes = pvc.getRequestedStorage();
		UUID uuid = UUID.randomUUID();

		removeInternalAnnotations(annotations);
		removeBlockedAnnotations(annotations);

		annotations.put(ANNOTATION_MANAGED_BY, ANNOTATION_STORAGE_PROVISIONER_NAME);
		annotations.put(ANNOTATION_VOLUME_UUID, uuid.toString());
		annotations.put(ANNOTATION_PVMANAGER_PVTAG, uuid.toString().substring(0,8));

		Map<String, String> cloneSourcePvAnnotations = null;
		V1PersistentVolume cloneSourcePersistentVolume = null;
		V1PersistentVolumeClaim cloneSourcePersistentVolumeClaim = null;

		String cloneFrom = annotations.get(ANNOTATION_CLONEFROM);
		if(cloneFrom != null && !cloneFrom.isEmpty()) {
			cloneSourcePersistentVolumeClaim = api.readNamespacedPersistentVolumeClaim(cloneFrom, pvc.getNamespace(), Boolean.TRUE.toString(), Boolean.TRUE, Boolean.FALSE);
			if(cloneSourcePersistentVolumeClaim == null) {
				LOG.error("Could not clone from '" + cloneFrom + "', it does not exist in this name space.");
				return false;
			}

			V1PersistentVolumeClaimSpec cloneSourceClaimSpec = cloneSourcePersistentVolumeClaim.getSpec();
			String cloneSourcePvName = cloneSourceClaimSpec.getVolumeName();
			if(cloneSourcePvName == null) {
				LOG.error("Could not clone from '" + cloneFrom + "', source persistent volume doesn't exist.");
				return false;
			}

			cloneSourcePersistentVolume = api.readPersistentVolume(cloneSourcePvName, null, Boolean.TRUE, Boolean.FALSE);
			if(cloneSourcePersistentVolume == null) {
				LOG.error("Persistent volume not ound for cloning: " + pvc.getNamespace() + "-" + cloneFrom + ": " + cloneSourcePvName);
				return false;
			}

			V1ObjectMeta metadata = cloneSourcePersistentVolume.getMetadata();
			if(metadata == null) {
				LOG.error("Persistent volume found for cloning has null metadata object: " + pvc.getNamespace() + "-" + cloneFrom);
				return false;
			}

			cloneSourcePvAnnotations = metadata.getAnnotations();
			if (cloneSourcePvAnnotations == null) {
				LOG.error("Persistent volume found for cloning has null annotations object: " + pvc.getNamespace() + "-" + cloneFrom);
				return false;
			}

			String managedBy = cloneSourcePvAnnotations.get(ANNOTATION_MANAGED_BY);
			if(!ANNOTATION_STORAGE_PROVISIONER_NAME.equals(managedBy)) {
				LOG.error("Persistent volume found for cloning but is not managed by pvmanager: " + pvc.getNamespace() + "-" + cloneFrom + ": " + managedBy);
				return false;
			}
		}

		PvVolumeBlockmode volumeMode = null;
		String volumeModeString = pvc.getClaimSpec().getVolumeMode();
		if(volumeModeString != null) {
			annotations.put(ANNOTATION_STORAGE_VOLUMEMODE, volumeModeString);
		}

		// Create a Claim Reference pointing to the PVC to allow the PV to link back to it directly ensuring it only gets mounted by its intended target
		V1ObjectReference claimRef = new V1ObjectReference();
		claimRef.setNamespace(pvc.getNamespace());
		claimRef.setName(pvc.getVolumeName());
		claimRef.setUid(pvc.getClaimUid());


		if(createPersistentVolume(client, claimRef, annotations, cloneSourcePersistentVolumeClaim, cloneSourcePersistentVolume, cloneSourcePvAnnotations, requestedStorageInBytes, pvc.getClaimSpec().getAccessModes()) == null) {
			return false;
		}

		return true;
	}

	public enum PvVolumeBlockmode {
		Filesystem,
		Block
	}
	private V1PersistentVolume createPersistentVolume(ApiClient client, V1ObjectReference claimRef, Map<String, String> annotations, V1PersistentVolumeClaim cloneSourcePersistentVolumeClaim, V1PersistentVolume cloneSourcePersistentVolume, Map<String,String> cloneSourcePvAnnotations, BigDecimal sizeInBytes, List<String> accessModes) throws Exception {
		final String requestedStorageeClass = getStorageClassFromAnnotations(annotations);
		final StorageProvider provider = storageControllerConfiguration.getStorageProviders().get(requestedStorageeClass);

		UUID uuid = UUID.fromString(annotations.get(ANNOTATION_VOLUME_UUID));

		CoreV1Api api = new CoreV1Api(client);

		V1PersistentVolumeSpec persistentVolumeProperties = null;

		String pvName = replaceTokensInString(annotations, provider.getPvNameFormat());
		try {
			annotations.put(ANNOTATION_PVMANAGER_PVREF, pvName);
			persistentVolumeProperties = provider.createPersistentVolume(annotations, Long.valueOf(sizeInBytes.toPlainString()));
			persistentVolumeProperties.setAccessModes(accessModes);
			persistentVolumeProperties.setClaimRef(claimRef);
		} finally {
			annotations.remove(ANNOTATION_PVMANAGER_PVTAG);
			annotations.remove(ANNOTATION_PVMANAGER_PVREF);
			annotations.remove(ANNOTATION_PVMANAGER_RECLAIM_POLICY);
			annotations.remove(ANNOTATION_CLONEREF);
		}
		if (persistentVolumeProperties == null) {
			throw new Exception("Persistent volume request could not be fulfilled by any providers.");
		}

		V1ObjectMeta metadata = new V1ObjectMeta();
		metadata.setName(pvName);
		metadata.setAnnotations(annotations);

		V1PersistentVolume pvTemplate = new V1PersistentVolume();
		pvTemplate.setSpec(persistentVolumeProperties);
		pvTemplate.setMetadata(metadata);
		V1PersistentVolume npv = api.createPersistentVolume(pvTemplate, null);

		if(annotations.containsKey(ANNOTATION_CLONESNAPSHOT)) {
			String cloneFrom = annotations.get(ANNOTATION_CLONEFROM);
			LOG.info("PV cloned for PVC " + claimRef.getNamespace() + "-" + claimRef.getName() + " -> " + npv.getMetadata().getName() + " on " + persistentVolumeProperties.getNfs().getServer() + ":" + persistentVolumeProperties.getNfs().getPath() + " [cloned from " + cloneFrom +"]");
		} else {
			LOG.info("PV created for PVC " + claimRef.getNamespace() + "-" + claimRef.getName() + " -> " + npv.getMetadata().getName() + " on " + persistentVolumeProperties.getNfs().getServer() + ":" + persistentVolumeProperties.getNfs().getPath());
		}
		return npv;
	}

	private void removeBlockedAnnotations(Map<String,String> annotations) {
		storageControllerConfiguration.getStorageProviders().get(getStorageClassFromAnnotations(annotations)).getBlockedAnnotations().forEach(blockedAnnotation -> annotations.remove(blockedAnnotation));
	}

	private void removeInternalAnnotations(Map<String, String> annotations) {
		annotations.remove(ANNOTATION_CLONEREF);
		annotations.remove(ANNOTATION_CLONESNAPSHOT);
	}
}
