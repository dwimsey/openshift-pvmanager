package com.shackspacehosting.engineering.pvmanager.storage.providers;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.base.Strings;
import com.shackspacehosting.engineering.pvmanager.kubernetes.PVClaimManagerService;
import com.shackspacehosting.engineering.pvmanager.storage.IStorageManagementProvider;
import com.shackspacehosting.engineering.pvmanager.storage.StorageProvider;
import feign.Feign;
import feign.Param;
import feign.RequestLine;
import feign.auth.BasicAuthRequestInterceptor;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import feign.slf4j.Slf4jLogger;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.models.V1NFSVolumeSource;
import io.kubernetes.client.models.V1PersistentVolumeSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.shackspacehosting.engineering.pvmanager.kubernetes.PVClaimManagerService.ANNOTATION_ATIME;
import static com.shackspacehosting.engineering.pvmanager.kubernetes.PVClaimManagerService.ANNOTATION_BASE;
import static com.shackspacehosting.engineering.pvmanager.kubernetes.PVClaimManagerService.ANNOTATION_BLOCKSIZE;
import static com.shackspacehosting.engineering.pvmanager.kubernetes.PVClaimManagerService.ANNOTATION_CASESENSITIVE;
import static com.shackspacehosting.engineering.pvmanager.kubernetes.PVClaimManagerService.ANNOTATION_CLONEREF;
import static com.shackspacehosting.engineering.pvmanager.kubernetes.PVClaimManagerService.ANNOTATION_CLONESNAPSHOT;
import static com.shackspacehosting.engineering.pvmanager.kubernetes.PVClaimManagerService.ANNOTATION_COMPRESSION_MODE;
import static com.shackspacehosting.engineering.pvmanager.kubernetes.PVClaimManagerService.ANNOTATION_EXEC;
import static com.shackspacehosting.engineering.pvmanager.kubernetes.PVClaimManagerService.ANNOTATION_PROVIDER_TYPE;
import static com.shackspacehosting.engineering.pvmanager.kubernetes.PVClaimManagerService.ANNOTATION_SNAPDIR;
import static com.shackspacehosting.engineering.pvmanager.kubernetes.PVClaimManagerService.ANNOTATION_STORAGE_MOUNTPROTOCOL;
import static com.shackspacehosting.engineering.pvmanager.kubernetes.PVClaimManagerService.ANNOTATION_STORAGE_VOLUMEMODE;
import static com.shackspacehosting.engineering.pvmanager.kubernetes.PVClaimManagerService.ANNOTATION_SYNC;
import static com.shackspacehosting.engineering.pvmanager.kubernetes.PVClaimManagerService.ANNOTATION_VOLUME_UUID;
import static com.shackspacehosting.engineering.pvmanager.kubernetes.PVClaimManagerService.PvVolumeBlockmode.Block;
import static com.shackspacehosting.engineering.pvmanager.storage.providers.FreeNasApiStorageProvider.QuotaMode.BOTH;
import static com.shackspacehosting.engineering.pvmanager.storage.providers.FreeNasApiStorageProvider.QuotaMode.UNLIMITED;
import static com.shackspacehosting.engineering.pvmanager.storage.providers.FreeNasApiStorageProvider.QuotaMode.QUOTA;
import static com.shackspacehosting.engineering.pvmanager.storage.providers.FreeNasApiStorageProvider.QuotaMode.RESERVE;
import static io.kubernetes.client.custom.Quantity.Format.BINARY_SI;

public class FreeNasApiStorageProvider implements IStorageManagementProvider, AutoCloseable {
	private static final Logger LOG = LoggerFactory.getLogger(FreeNasApiStorageProvider.class);


	// We're creating our own object mapper here because we have special parameters to it that we dont' want to share with others at this time
	// @TODO we should figure out a better method to share object mappers since they are slow to create
	private static ObjectMapper feignObjectMapper = new ObjectMapper()
			.setSerializationInclusion(JsonInclude.Include.NON_NULL)
			.configure(SerializationFeature.INDENT_OUTPUT, true)
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	final public static String ANNOTATION_VOLUME_HOST = ANNOTATION_BASE + "nfs-host";
	final public static String ANNOTATION_VOLUME_PATH = ANNOTATION_BASE + "nfs-path";
	final public static String ANNOTATION_VOLUME_EXPORT = ANNOTATION_BASE + "nfs-export";
	final public static String ANNOTATION_PROVIDER_TYPE_NAME = "nfs";

	final public static String ANNOTATION_PVMANAGER_PVREF = "pvmanager:pvref";

	final public static String CONFIG_FREENAS = "freenas";
	final public static String CONFIG_FREENAS_API_BASEURL = "endpointUri";
	final public static String CONFIG_FREENAS_API_CLIENTID = "clientId";
	final public static String CONFIG_FREENAS_API_CLIENTSECRET = "clientSecret";

	final public static String CONFIG_NFS = "nfs";
	final public static String CONFIG_NFS_HOSTNAME = "hostname";
	final public static String CONFIG_NFS_EXPORTROOT = "exportRoot";
	final public static String CONFIG_ZFS = "zfs";
	final public static String CONFIG_ZFS_ROOTPATH = "rootPath";
	final public static String CONFIG_ZFS_BECOMEROOT = "becomeRoot";
	final public static String CONFIG_ZFS_UNIXMODE = "unixMode";
	final public static String CONFIG_ZFS_QUOTAMODE = "quotaMode";
	final public static String CONFIG_ZFS_QUOTAMODE_DEFAULT = "QUOTA";

	final private StorageProvider provider;

	private boolean becomeRoot;
	private QuotaMode quotaMode;
	private Long unixMode = null;
	private int minAllowedRecordSize = 512;
	private int maxBlockSize = 131072; // 1048576;


	public enum QuotaMode {
		IGNORE,
		UNLIMITED,
		QUOTA,
		RESERVE,
		BOTH;
	}


	private String nfsHostname;
	private String nfsRootPath;

	private String freenasApiUrl;
	private String freenasApiClientId;
	private String freenasApiClientSecret;

	private String zfsRootPath;

	public boolean isBecomeRoot() {
		return becomeRoot;
	}

	public void setBecomeRoot(boolean becomeRoot) {
		this.becomeRoot = becomeRoot;
	}

	public String getNfsHostname() {
		return nfsHostname;
	}

	public void setNfsHostname(String nfsHostname) {
		this.nfsHostname = nfsHostname;
	}

	public String getNfsRootPath() {
		return nfsRootPath;
	}

	public void setNfsRootPath(String nfsRootPath) {
		this.nfsRootPath = nfsRootPath;
	}

	public String getZfsRootPath() {
		return zfsRootPath;
	}

	public void setZfsRootPath(String zfsRootPath) {
		this.zfsRootPath = zfsRootPath;
	}


	public enum ZfsSyncModes {
		Standard,
		Always,
		Disabled
	}

	public class ZfsSnapshotRequest {
		String filesystem = "";
		String name = "";
		boolean recursive = true;
		boolean vmware_sync = false;
	}

	public class ZfsSnapshotProperties {
		String filesystem = "";
		String snapshotName = "";
		String id = "";
		boolean mostRecent = true;
		String parentType = "filesystem";
		long refer = 0;
		long used = 0;
	}

	public class ZfsCloneRequest {
		String name = "";
	}

	public class ZfsCreateDatasetRequest {
		public String name;
		public String type;
		public Long volsize;
		public Long volblocksize;
		public Boolean sparse;
		public Boolean force_size;
		public String comment;
		public ZfsSyncModes sync;
		public String compression;
		public Boolean atime;
		public Boolean exec;
		public Long quota;

		public Long refquota;

		public Long reservation;

		public Long refreservation;

		public Long copies;

		public String snapdir;
		public Boolean deduplication;
		public Boolean readonly;

		public Long recordsize;
		public String casesensitivity;
		public String share_type;
	}

	public enum ZfsVolumeType {
		FILESYSTEM,
		VOLUME
	}

	public class ZfsDatasetProperties {
		public String id;
		public String name;
		public String pool;
		public String mountpoint;
		public ZfsVolumeType type;
		public String compression;
		public String deduplication;
		public Boolean exec;
		public Long quota;
		public Long refquota;
		public Long reserve;
		public Long refreserve;
		public Long recordsize;
		public ZfsSyncModes sync = ZfsSyncModes.Standard;
		public String share_type;
		public Boolean atime;
		public String snapdir;
		public Long copies;
		public Boolean readonly;
		public Boolean casesensitivity;
		public String origin;
		public List<Object> children;
		public BigDecimal compressionratio;



		public Long avail;
		public Long used;
		public String comments;
	}

	public class NfsCreateShareProperties {
		public Long id;
		public String[] paths;
		public String comment;
		public String[] networks;
		public String[] hosts;
		public Boolean alldirs;
		public Boolean ro;
		public Boolean quiet;
		public String maproot_user;
		public String maproot_group;
		public String mapall_user;
		public String mapall_group;
		public String[] security;
	}

	public interface FreeNASAPI {
		// pool == zfs01, basepath = openshift/persistentvolumes/basic, datasetname = guid from dataset name (final path component of zfs filesystem path)
		//@RequestLine("GET /api/v1.0/storage/dataset//api/v1.0/storage/dataset/zfs01/openshift/persistentvolumes/basic/")
		@RequestLine("GET /api/v1.0/storage/dataset/{zfsPool}/{basePath}/{datasetName}")
		ZfsDatasetProperties getDataset(@Param("zfsPool") String zfsPool, @Param("basePath") String basePath, @Param("datasetName") String datasetName);

		@RequestLine("GET /api/v1.0/storage/dataset/{basePath}/{datasetName}")
		ZfsDatasetProperties getDataset(@Param("basePath") String basePath, @Param("datasetName") String datasetName);

		@RequestLine("GET /api/v1.0/storage/dataset/{zfsPath}")
		ZfsDatasetProperties getDataset(@Param("zfsPath") String zfsPath);

		@RequestLine("POST /api/v1.0/storage/snapshot/")
		ZfsSnapshotProperties createSnapshot(ZfsSnapshotRequest snapshotRequest);

		@RequestLine("POST /api/v1.0/storage/snapshot/{snapshotName}/clone/")
		void createClone(@Param("snapshotName") String snapshotName, ZfsCloneRequest zfsCloneRequest);

		@RequestLine("POST /api/v2.0/pool/dataset")
		HashMap<String, Object> createDataset(ZfsCreateDatasetRequest cdsr);

		@RequestLine("POST /api/v2.0/sharing/nfs")
		HashMap<String, Object> shareDataset(NfsCreateShareProperties zfsDataset);

	}
	FreeNASAPI apiEndpoint = null;

	public FreeNasApiStorageProvider(StorageProvider provider, JsonNode cfgNode) throws IOException {
		this.provider=provider;
		boolean hasError = false;

		JsonNode nfsCfgNode = cfgNode.get(CONFIG_NFS);
		if (nfsCfgNode != null) {
			if(nfsCfgNode.has(CONFIG_NFS_HOSTNAME)) {
				this.nfsHostname = nfsCfgNode.get(CONFIG_NFS_HOSTNAME).asText();
			} else {
				LOG.error("No NFS hostname configured");
				hasError = true;
			}

			if(nfsCfgNode.has(CONFIG_NFS_EXPORTROOT)) {
				this.nfsRootPath = nfsCfgNode.get(CONFIG_NFS_EXPORTROOT).asText();
			} else {
				LOG.error("No NFS export root configured");
				hasError = true;
			}
		}

		JsonNode freenasCfgNode = cfgNode.get(CONFIG_FREENAS);
		if(freenasCfgNode != null) {
			if(freenasCfgNode.has(CONFIG_FREENAS_API_BASEURL)) {
				this.freenasApiUrl = freenasCfgNode.get(CONFIG_FREENAS_API_BASEURL).asText();
			} else {
				LOG.error("No FreeNAS API URL configured");
				hasError = true;
			}
			if(freenasCfgNode.has(CONFIG_FREENAS_API_CLIENTID)) {
				this.freenasApiClientId = freenasCfgNode.get(CONFIG_FREENAS_API_CLIENTID).asText();
			} else {
				LOG.error("No FreeNAS API id configured");
				hasError = true;
			}
			if(freenasCfgNode.has(CONFIG_FREENAS_API_CLIENTSECRET)) {
				this.freenasApiClientSecret = freenasCfgNode.get(CONFIG_FREENAS_API_CLIENTSECRET).asText();
			} else {
				LOG.error("No FreeNAS API secret configured");
				hasError = true;
			}

		}

		JsonNode zfsCfgNode = cfgNode.get(CONFIG_ZFS);
		if(zfsCfgNode != null) {
			if(zfsCfgNode.has(CONFIG_ZFS_ROOTPATH)) {
				this.zfsRootPath = zfsCfgNode.get(CONFIG_ZFS_ROOTPATH).asText();
			} else {
				LOG.error("No ZFS root path configured.");
				hasError = true;
			}

			if(zfsCfgNode.has(CONFIG_ZFS_BECOMEROOT)) {
				this.becomeRoot = zfsCfgNode.get(CONFIG_ZFS_BECOMEROOT).asBoolean(false);
			} else {
				this.becomeRoot = false;
				LOG.warn("No ZFS become root configured, assuming false.");
			}

			if(zfsCfgNode.has(CONFIG_ZFS_QUOTAMODE)) {
				this.quotaMode = QuotaMode.valueOf(zfsCfgNode.get(CONFIG_ZFS_QUOTAMODE).asText(CONFIG_ZFS_QUOTAMODE_DEFAULT).toUpperCase());
			} else {
				this.quotaMode = QuotaMode.valueOf(CONFIG_ZFS_QUOTAMODE_DEFAULT);
				LOG.info("No ZFS quota mode configured, using default: " + CONFIG_ZFS_QUOTAMODE_DEFAULT);
			}

			if(zfsCfgNode.has(CONFIG_ZFS_UNIXMODE)) {
				this.unixMode = Long.valueOf(zfsCfgNode.get(CONFIG_ZFS_UNIXMODE).asText());
			} else {
				LOG.warn("No ZFS unix mode configured.");
			}
		}

		if(hasError) {
			throw new IllegalArgumentException("NFS configuration has one or more unrecoverable errors.");
		}
		this.init();
	}

	public void init() {
		Feign.Builder feignBuilder = Feign.builder()
			.encoder(new JacksonEncoder(feignObjectMapper))
			.decoder(new JacksonDecoder(feignObjectMapper));



		if(!Strings.isNullOrEmpty(freenasApiClientId) && !Strings.isNullOrEmpty(freenasApiClientSecret)) {
			feignBuilder = feignBuilder.requestInterceptor(new BasicAuthRequestInterceptor(freenasApiClientId, freenasApiClientSecret));
		}

		feignBuilder =
		//.client(new OkHttpClient())
				feignBuilder.logger(new Slf4jLogger(FreeNASAPI.class)) //not working

				.logLevel(feign.Logger.Level.FULL);
		apiEndpoint = feignBuilder.target(FreeNASAPI.class, freenasApiUrl);
	}

	Long parseZfsLongProperty(Map<String, Object> lookupMap, String propertyName) {
		if(lookupMap == null) {
			return null;
		}
		Map<String, Object> propertyMap = (Map<String,Object>) lookupMap.get(propertyName);
		if(propertyMap == null) {
			return null;
		}
		if("DEFAULT".equals(propertyMap.get("source"))) {
			return null;
		}

		Object o = propertyMap.get("parsed");
		if( o != null) {
			if (o instanceof Long) {
				return Long.valueOf((Long)o);
			}
			if (o instanceof Integer) {
				return Long.valueOf((Integer)o);
			}
		}

		return null;
	}

	String parseZfsStringProperty(Map<String, Object> lookupMap, String propertyName) {
		if(lookupMap == null) {
			return null;
		}
		Map<String, String> propertyMap = (Map<String,String>) lookupMap.get(propertyName);
		if(propertyMap == null) {
			return null;
		}
		if("DEFAULT".equals(propertyMap.get("source"))) {
			return null;
		}

		return propertyMap.get("parsed");
	}

	Boolean parseZfsBooleanProperty(Map<String, Object> lookupMap, String propertyName) {
		if(lookupMap == null) {
			return null;
		}
		Map<String, String> propertyMap = (Map<String,String>) lookupMap.get(propertyName);
		if(propertyMap == null) {
			return null;
		}
		if("DEFAULT".equals(propertyMap.get("source"))) {
			return null;
		}

		switch(propertyMap.get("parsed").toLowerCase()) {
			case "off":
			case "false":
			case "no":
			case "":
			case "0":
				return new Boolean(false);
			default:
				return new Boolean(true);
		}
	}

	ZfsSyncModes parseZfsSyncProperty(Map<String, Object> lookupMap, String propertyName) {
		if(lookupMap == null) {
			return null;
		}
		Map<String, String> propertyMap = (Map<String,String>) lookupMap.get(propertyName);
		if(propertyMap == null) {
			return null;
		}
		if("DEFAULT".equals(propertyMap.get("source"))) {
			return null;
		}

		return ZfsSyncModes.valueOf(propertyMap.get("parsed"));
	}

	private BigDecimal parseZfsBigDecimalProperty(Map<String, Object> lookupMap, String propertyName) {
		if(lookupMap == null) {
			return null;
		}
		Map<String, String> propertyMap = (Map<String,String>) lookupMap.get(propertyName);
		if(propertyMap == null) {
			return null;
		}
		if("DEFAULT".equals(propertyMap.get("source"))) {
			return null;
		}

		return new BigDecimal(propertyMap.get("parsed"));
	}

	ZfsDatasetProperties datasetFromCreateApiResponse(Map<String, Object> c) {
		if(c == null) {
			return null;
		}

		ZfsDatasetProperties r = new ZfsDatasetProperties();

		r.mountpoint = (String) c.get("mountpoint");
		r.id = (String) c.get("id");
		r.name = (String) c.get("name");
		r.pool = (String) c.get("pool");
		r.type = (c.containsKey("type") ? ZfsVolumeType.valueOf((String) c.get("type").toString()) : null);
		r.quota = parseZfsLongProperty(c, "quota");
		r.refquota = parseZfsLongProperty(c,"refquota");
		r.reserve = parseZfsLongProperty(c,"reservation");
		r.refreserve = parseZfsLongProperty(c,"refreservation");
		r.recordsize = parseZfsLongProperty(c,"recordsize");
		r.deduplication = parseZfsStringProperty(c,"deduplication");
		r.sync = parseZfsSyncProperty(c,"sync");
		r.exec = parseZfsBooleanProperty(c,"exec");
		r.compression = parseZfsStringProperty(c,"compression");

		r.share_type = (String) c.get("share_type");
		r.atime = parseZfsBooleanProperty(c,"atime");
		r.origin = parseZfsStringProperty(c,"origin");
		r.compressionratio = parseZfsBigDecimalProperty(c,"compressionratio");
		r.snapdir = parseZfsStringProperty(c,"snapdir");
		r.copies = parseZfsLongProperty(c, "copies");
		r.readonly = parseZfsBooleanProperty(c,"readonly");
		r.casesensitivity = parseZfsBooleanProperty(c,"casesensitivity");
		r.children = (List<Object>)c.get("children");

		return r;
	}

	NfsCreateShareProperties nfsShareFromCreateApiResponse(Map<String, Object> c) {
		if(c == null) {
			return null;
		}

		NfsCreateShareProperties r = new NfsCreateShareProperties();

		//r.id = (String) c.get("id");
		Object prop;
		prop = c.get("paths");
		if(prop != null) {
			if(prop instanceof List) {
				r.paths = ((List<String>)prop).toArray(new String[0]);
			}
		}
		r.comment = (String) c.get("comment");

		prop = c.get("networks");
		if(prop != null) {
			if(prop instanceof List) {
				r.networks = ((List<String>)prop).toArray(new String[0]);
			}
		}
		prop = c.get("hosts");
		if(prop != null) {
			if(prop instanceof List) {
				r.hosts = ((List<String>)prop).toArray(new String[0]);
			}
		}
		r.alldirs = (Boolean) c.get("alldirs");
		r.ro = (Boolean) c.get("ro");
		r.quiet = (Boolean) c.get("quiet");
		r.maproot_user = (String) c.get("maproot_user");
		r.maproot_group = (String) c.get("maproot_group");
		r.mapall_user = (String) c.get("mapall_user");
		r.mapall_group = (String) c.get("mapall_group");
		prop = c.get("security");
		if(prop != null) {
			if(prop instanceof List) {
				r.security = ((List<String>)prop).toArray(new String[0]);
			}
		}

		return r;
	}

	final private static DateTimeFormatter snapshotTimestampFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
	public V1PersistentVolumeSpec createPersistentVolume(Map<String, String> annotations, long sizeInBytes) throws Exception {
		PVClaimManagerService.PvVolumeBlockmode volumeMode = PVClaimManagerService.PvVolumeBlockmode.Filesystem;
		String volumeModeString = annotations.get(ANNOTATION_STORAGE_VOLUMEMODE);
		if(!Strings.isNullOrEmpty(volumeModeString)) {
			volumeMode = PVClaimManagerService.PvVolumeBlockmode.valueOf(volumeModeString);
		}

		if(volumeMode == Block) {
			throw new UnsupportedOperationException("Block mode is not currently supported with SSH management protocol and NFS mount protocol.");
		}

		String mountProtocolString = annotations.get(ANNOTATION_STORAGE_MOUNTPROTOCOL);
		if(mountProtocolString != null) {
			if(!"nfs".equalsIgnoreCase(mountProtocolString)) {
				throw new UnsupportedOperationException(mountProtocolString + " mount protocol is not supported for FreeNAS API.");
			}
		} else {
			// If its null we assume NFS since thats all this module supports currently anyway
			annotations.put(ANNOTATION_STORAGE_MOUNTPROTOCOL, "nfs");
		}


		String volumeUuid = annotations.get(ANNOTATION_VOLUME_UUID);
		String zfsVolumePath = Paths.get(zfsRootPath, volumeUuid).toString();
		String exportPath = Paths.get(nfsRootPath, volumeUuid).toString();

		annotations.put(ANNOTATION_VOLUME_HOST, nfsHostname);
		annotations.put(ANNOTATION_VOLUME_PATH, zfsVolumePath);
		annotations.put(ANNOTATION_VOLUME_EXPORT, exportPath);
		annotations.put(ANNOTATION_PROVIDER_TYPE, ANNOTATION_PROVIDER_TYPE_NAME);

		ZfsDatasetProperties datasetProperties = null;
		StringBuilder outputBuffer;
		String zfsCloneRef = annotations.get(ANNOTATION_CLONEREF);
		if(zfsCloneRef != null) {
			String[] parts = zfsCloneRef.split(":");
			if(parts.length != 2) {
				LOG.error("Could not parse clone reference, expected 2 parts got " + parts.length + ": " + zfsCloneRef);
				return null;
			}
			String nfsHost = parts[0];
			String zfsFilesystem = parts[1];

			if(!nfsHost.equals(getNfsHostname())) {
				LOG.error("Management host for clone source is different than this provider: " + getNfsHostname() + " != " + nfsHost);
				return null;
			}

			if(!zfsFilesystem.startsWith(getZfsRootPath()) && ((zfsFilesystem.length() - 36) == getZfsRootPath().length())) {
				LOG.error("Management host for clone source is different than this provider: " + getZfsRootPath() + " != " + zfsFilesystem);
				return null;
			}

			String zfsSnapshotName = "pvmanager-" + annotations.get(ANNOTATION_PVMANAGER_PVREF) + "-" + OffsetDateTime.now().format(snapshotTimestampFormatter);
			String zfsFullSnapshotPath = zfsFilesystem + zfsSnapshotName;
			annotations.put(ANNOTATION_CLONESNAPSHOT, zfsFullSnapshotPath);



			ZfsSnapshotRequest zfsSnapshotRequest = new ZfsSnapshotRequest();
			zfsSnapshotRequest.filesystem = zfsFilesystem;
			zfsSnapshotRequest.name = zfsSnapshotName;
			ZfsSnapshotProperties snapshotProperties = apiEndpoint.createSnapshot(zfsSnapshotRequest);

//			command = (becomeRoot ? "sudo " : "") + "zfs snapshot -o " + ANNOTATION_PVMANAGER_PVREF + "=" +
//					annotations.get(ANNOTATION_PVMANAGER_PVREF) + " " + zfsFullSnapshotPath;
//			outputBuffer = new StringBuilder();
//			cmdReturnValue = sshWrapper.exec(command, outputBuffer);
//			if (cmdReturnValue != 0) {
//				LOG.error("zfs snapshot volume failed: exit status: " + cmdReturnValue);
//				LOG.error(outputBuffer.toString());
//				return null;
//			}
//			outputBuffer = new StringBuilder();
//			cmdReturnValue = sshWrapper.exec(command, outputBuffer);
//			if (cmdReturnValue != 0) {
//				LOG.error("zfs " + ((zfsCloneRef != null) ? "clone" : "create") + " volume failed: exit status: " + cmdReturnValue);
//				LOG.error(outputBuffer.toString());
//				return null;
//			}


			ZfsCloneRequest zfsCloneRequest = new ZfsCloneRequest();
			zfsCloneRequest.name = zfsVolumePath;
			apiEndpoint.createClone(snapshotProperties.snapshotName, zfsCloneRequest);


			// @TODO At this point we need to reapply annotations that may have changed where possible
			datasetProperties = apiEndpoint.getDataset(zfsRootPath, volumeUuid);
		} else {

			ZfsCreateDatasetRequest cdsr;
			cdsr = new ZfsCreateDatasetRequest();
			cdsr.name = zfsVolumePath;
			cdsr.type = "FILESYSTEM";

			String blockSizeStr = annotations.get(ANNOTATION_BLOCKSIZE);
			if(blockSizeStr != null) {
				int recordsize = Integer.valueOf(blockSizeStr);
				// Make sure the recordsize is between 512 and maxBlockSize bytes and is a power of two
				if(recordsize >= minAllowedRecordSize && recordsize <= maxBlockSize && ((recordsize & (recordsize - 1)) == 0)) {
					cdsr.recordsize = new Long(recordsize);
					sizeInBytes = sizeInBytes >= 0 ? ((sizeInBytes + recordsize - 1) / recordsize) * recordsize : (sizeInBytes / recordsize) * recordsize;
				}
			}

			if("VOLUME".equals(cdsr.type)) {
				cdsr.volblocksize = cdsr.recordsize;
				cdsr.volsize = sizeInBytes;
			}

			if(quotaMode == UNLIMITED) {
				// @TODO Implement unlimited quota in a better way
				cdsr.quota = new Long(Long.MAX_VALUE);
			}
			if(quotaMode == QUOTA || quotaMode == BOTH) {
				cdsr.quota = new Long(sizeInBytes);
			}
			if(quotaMode == RESERVE || quotaMode == BOTH) {
				cdsr.reservation = new Long(sizeInBytes);
			}


//			String checksumMode = annotations.get(ANNOTATION_CHECKSUM_MODE);
//			if(checksumMode != null) {
//				switch(checksumMode.toLowerCase()) {
//					case "on":
//					case "off":
//					case "fletcher2":
//					case "fletcher4":
//					case "sha256":
//					case "noparity":
//					case "sha512":
//					case "skein":
//						cdsr.checksum = checksumMode.toLowerCase();
//						break;
//					default:
//						LOG.warn("Unexpected " + ANNOTATION_CHECKSUM_MODE + " annotation value: " + checksumMode);
//						break;
//				}
//			}

			String compression = annotations.get(ANNOTATION_COMPRESSION_MODE);
			if(compression != null) {
				switch(compression.toLowerCase()) {
					case "on":
					case "off":
					case "lzjb":
					case "zle":
					case "lz4":
					case "gzip":
					case "gzip-1":
					case "gzip-2":
					case "gzip-3":
					case "gzip-4":
					case "gzip-5":
					case "gzip-6":
					case "gzip-7":
					case "gzip-8":
					case "gzip-9":
						cdsr.compression = compression.toLowerCase();
						break;
					default:
						LOG.warn("Unexpected " + ANNOTATION_COMPRESSION_MODE + " annotation value: " + compression);
						break;
				}
			}
			String atimeMode = annotations.get(ANNOTATION_ATIME);
			if(atimeMode != null) {
				switch(atimeMode.toLowerCase()) {
					case "on":
						cdsr.atime = new Boolean(true);
						break;
					case "off":
						cdsr.atime = new Boolean(false);
						break;
					default:
						LOG.warn("Unexpected " + ANNOTATION_ATIME + " annotation value: " + atimeMode);
						break;
				}
			}
			String execMode = annotations.get(ANNOTATION_EXEC);
			if(execMode != null) {
				switch(execMode.toLowerCase()) {
					case "on":
						cdsr.exec = new Boolean(true);
						break;
					case "off":
						cdsr.exec = new Boolean(false);
						break;
					default:
						LOG.warn("Unexpected " + ANNOTATION_EXEC + " annotation value: " + execMode);
						break;
				}
			}

			String syncMode = annotations.get(ANNOTATION_SYNC);
			if(syncMode != null) {
				switch(syncMode.toLowerCase()) {
					case "standard":
					case "always":
					case "disabled":
//						cdsr.sync = case
						//extraArgs = extraArgs + " -o sync=" + syncMode.toLowerCase();
						break;
					default:
						LOG.warn("Unexpected " + ANNOTATION_SYNC + " annotation value: " + syncMode);
						break;
				}
			}
			String caseMode = annotations.get(ANNOTATION_CASESENSITIVE);
			if(caseMode != null) {
				switch(caseMode.toLowerCase()) {
					case "sensitive":
					case "insensitive":
					case "mixed":
						cdsr.casesensitivity = caseMode.toLowerCase();
						break;
					default:
						LOG.warn("Unexpected " + ANNOTATION_CASESENSITIVE + " annotation value: " + caseMode);
						break;
				}
			}











		/*


		String setUid = annotations.get(ANNOTATION_SETUID);
		if(setUid != null) {
			switch(setUid.toLowerCase()) {
				case "on":
				case "off":
					extraArgs = extraArgs + " -o setuid=" + setUid.toLowerCase();
					break;
				default:
					LOG.warn("Unexpected " + ANNOTATION_SETUID + " annotation value: " + setUid);
					break;
			}
		}
		String logMode = annotations.get(ANNOTATION_LOGBIAS);
		if(logMode != null) {
			switch(logMode.toLowerCase()) {
				case "latency":
				case "throughput":
					extraArgs = extraArgs + " -o logbias=" + logMode.toLowerCase();
					break;
				default:
					LOG.warn("Unexpected " + ANNOTATION_LOGBIAS + " annotation value: " + logMode);
					break;
			}
		}



*/












			String snapMode = annotations.get(ANNOTATION_SNAPDIR);
			if(snapMode != null) {
				switch(snapMode.toLowerCase()) {
					case "hidden":
					case "visible":
						cdsr.snapdir = snapMode.toLowerCase();
						break;
					default:
						LOG.warn("Unexpected " + ANNOTATION_SNAPDIR + " annotation value: " + snapMode);
						break;
				}
			}





//
//			// Add user attribute pointing to the name of the PV that was created for it
//			extraArgs = extraArgs + " -o " + ANNOTATION_PVMANAGER_PVREF + "=" +
//					annotations.get(ANNOTATION_PVMANAGER_PVREF);

			datasetProperties = datasetFromCreateApiResponse(apiEndpoint.createDataset(cdsr));

		}

		NfsCreateShareProperties shareProperties = shareDatasetFromApiResponse(datasetProperties);

		if(unixMode != null) {
			// @TODO Implement unix mode setting here if not block mode
		}

		V1PersistentVolumeSpec spec = new V1PersistentVolumeSpec();
		// Because pvmanager does not plugin to Kubernetes like the existing persistent volume engines, only the 'Retain'
		// reclaimPolicy is set.  pvmanager emulates the 'Delete' policy be deleting release persistent volumes itself.
		spec.setPersistentVolumeReclaimPolicy("Retain");

		Map<String, Quantity> capacity = new HashMap<String, Quantity>();
		capacity.put("storage", new Quantity(new BigDecimal(sizeInBytes), BINARY_SI));
		spec.setCapacity(capacity);

		V1NFSVolumeSource nfsSource = new V1NFSVolumeSource();
		nfsSource.setServer(nfsHostname);
		nfsSource.setPath(datasetProperties.mountpoint);
		nfsSource.setReadOnly((datasetProperties.readonly == null ? false : datasetProperties.readonly));
		spec.setNfs(nfsSource);

		return spec;
	}

	private NfsCreateShareProperties shareDatasetFromApiResponse(ZfsDatasetProperties datasetProperties) {
		// Now we need to share the new data set
		NfsCreateShareProperties ncsr = new NfsCreateShareProperties();
		ncsr.ro = datasetProperties.readonly;

		final String[] paths = new String[1];
		paths[0] = datasetProperties.mountpoint;
		ncsr.paths = paths;

		return nfsShareFromCreateApiResponse(apiEndpoint.shareDataset(ncsr));

	}

	@Override
	public void removePersistentVolume(Map<String, String> annotations) throws Exception {
		String suffixChar = null;
		if(annotations == null) {
			throw new IllegalArgumentException("No annotations provided for persistent volume, " + ANNOTATION_VOLUME_UUID + " is required to delete volumes");
		}
		String uuid = annotations.get(ANNOTATION_VOLUME_UUID);
		if(uuid == null) {
			throw new IllegalArgumentException(ANNOTATION_VOLUME_UUID + " annotation not found, " + ANNOTATION_VOLUME_UUID + " is required to delete volumes");
		}
		// Convert it to a UUID object and then u.toString() to ensure theres no funny business being crafted here to break out of the shell
		UUID u = UUID.fromString(uuid);
		String command;
		StringBuilder outputBuffer;
		int xs;

		command = (becomeRoot ? "sudo " : "") + "zfs destroy " + Paths.get(zfsRootPath, u.toString()).toString();

		outputBuffer = new StringBuilder();
//		xs = sshWrapper.exec(command, outputBuffer);
//		if (xs != 0) {
//			LOG.error("zfs destroy volume failed: exit status: " + xs + ": filesytem orphan: " + Paths.get(zfsRootPath, u.toString()).toString());
//			LOG.error(outputBuffer.toString());
//		}
//
//		String sourceSnapshot = annotations.get(ANNOTATION_CLONESNAPSHOT);
//		if(sourceSnapshot != null && !sourceSnapshot.isEmpty()) {
//			if(!sourceSnapshot.startsWith(getZfsRootPath())) {
//				LOG.error("Could not destroy snapshot, snapshot does not start with zfsRootPath: " + getZfsRootPath() + " != " + sourceSnapshot);
//				return;
//			}
//			if(!sourceSnapshot.contains("@")) {
//				LOG.error("Could not destroy snapshot, snapshot does not start with zfsRootPath: " + getZfsRootPath() + " != " + sourceSnapshot);
//				return;
//			}
//			command = (becomeRoot ? "sudo " : "") + "zfs destroy " + sourceSnapshot;
//
//			outputBuffer = new StringBuilder();
//			xs = sshWrapper.exec(command, outputBuffer);
//			if (xs != 0) {
//				LOG.error("zfs destroy snapshot failed: exit status: " + xs + ": snapshot orphan: " + sourceSnapshot);
//				LOG.error(outputBuffer.toString());
//			}
//		}

	}

	@Override
	public void close() throws Exception {
	}
}
