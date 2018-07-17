package com.shackspacehosting.engineering.openshiftpvmanager.storage.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.shackspacehosting.engineering.openshiftpvmanager.SSHExecWrapper;
import com.shackspacehosting.engineering.openshiftpvmanager.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import static com.shackspacehosting.engineering.openshiftpvmanager.PVClaimManagerService.*;

public class NFS implements IStorageManagementProvider, AutoCloseable {
	private static final Logger LOG = LoggerFactory.getLogger(NFS.class);

	final public static String ANNOTATION_VOLUME_HOST = ANNOTATION_BASE + "nfs-host";
	final public static String ANNOTATION_VOLUME_PATH = ANNOTATION_BASE + "nfs-path";
	final public static String ANNOTATION_VOLUME_EXPORT = ANNOTATION_BASE + "nfs-export";
	final public static String ANNOTATION_PROVIDER_TYPE_NAME = "nfs";

	final public static String ANNOTATION_PVMANAGER_PVREF = "pvmanager:pvref";

	final public static String CONFIG_NFS = "nfs";
	final public static String CONFIG_NFS_HOSTNAME = "hostname";
	final public static String CONFIG_NFS_EXPORTROOT = "exportRoot";
	final public static String CONFIG_SSH = "ssh";
	final public static String CONFIG_SSH_HOSTNAME = "hostname";
	final public static String CONFIG_SSH_PORT = "port";
	final public static int CONFIG_SSH_PORT_DEFAULT = 22;
	final public static String CONFIG_SSH_IDENTITY = "identity";
	final public static String CONFIG_SSH_PRIVATEKEY_FILE = "privateKeyFile";
	final public static String CONFIG_SSH_PRIVATEKEY = "privateKey";
	final public static String CONFIG_SSH_TOKEN = "token";
	final public static String CONFIG_ZFS = "zfs";
	final public static String CONFIG_ZFS_ROOTPATH = "rootPath";
	final public static String CONFIG_ZFS_BECOMEROOT = "becomeRoot";
	final public static String CONFIG_ZFS_UNIXMODE = "unixMode";
	final public static String CONFIG_ZFS_QUOTAMODE = "quotaMode";
	final public static String CONFIG_ZFS_QUOTAMODE_DEFAULT = "QUOTA";

	final private StorageProvider provider;

	private SSHExecWrapper sshWrapper;

	private boolean becomeRoot;
	private QuotaMode quotaMode;
	private Long unixMode = null;

	public enum QuotaMode {
		IGNORE,
		QUOTA,
		RESERVE,
		BOTH;
	}


	private String nfsHostname;
	private String nfsRootPath;

	private String sshHostname;
	private int sshPort;
	private String sshUsername;
	private String sshPrivateKey;
	private String sshToken;

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

	public String getSshHostname() {
		return sshHostname;
	}

	public void setSshHostname(String sshHostname) {
		this.sshHostname = sshHostname;
	}

	public int getSshPort() {
		return sshPort;
	}

	public void setSshPort(int sshPort) {
		this.sshPort = sshPort;
	}

	public String getSshUsername() {
		return sshUsername;
	}

	public void setSshUsername(String sshUsername) {
		this.sshUsername = sshUsername;
	}

	public String getSshPrivateKey() {
		return sshPrivateKey;
	}

	public void setSshPrivateKey(String sshPrivateKey) {
		this.sshPrivateKey = sshPrivateKey;
	}

	public String getSshToken() {
		return sshToken;
	}

	public void setSshToken(String sshToken) {
		this.sshToken = sshToken;
	}

	public String getZfsRootPath() {
		return zfsRootPath;
	}

	public void setZfsRootPath(String zfsRootPath) {
		this.zfsRootPath = zfsRootPath;
	}

	public NFS(StorageProvider provider, JsonNode cfgNode) throws IOException {
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

		JsonNode sshCfgNode = cfgNode.get(CONFIG_SSH);
		if(sshCfgNode != null) {
			if(sshCfgNode.has(CONFIG_SSH_HOSTNAME)) {
				this.sshHostname = sshCfgNode.get(CONFIG_SSH_HOSTNAME).asText();
			} else {
				LOG.error("No SSH hostname configured");
				hasError = true;
			}

			if(sshCfgNode.has(CONFIG_SSH_PORT)) {
				this.sshPort = sshCfgNode.get(CONFIG_SSH_PORT).asInt(CONFIG_SSH_PORT_DEFAULT);
			} else {
				LOG.trace("Using default SSH port: " + CONFIG_SSH_PORT_DEFAULT);
				this.sshPort = CONFIG_SSH_PORT_DEFAULT;
			}

			if(sshCfgNode.has(CONFIG_SSH_IDENTITY)) {
				this.sshUsername = sshCfgNode.get(CONFIG_SSH_IDENTITY).asText();
			} else {
				LOG.error("No SSH identity configured");
				hasError = true;
			}

			if(sshCfgNode.has(CONFIG_SSH_PRIVATEKEY_FILE)) {
				this.sshPrivateKey = sshCfgNode.get(CONFIG_SSH_PRIVATEKEY_FILE).asText();
				//this.sshPrivateKey = new String(Files.readAllBytes(Paths.get(sshCfgNode.get(CONFIG_SSH_PRIVATEKEY_FILE).asText())));
			} else if(sshCfgNode.has(CONFIG_SSH_PRIVATEKEY)) {
				this.sshPrivateKey = sshCfgNode.get(CONFIG_SSH_PRIVATEKEY).asText();
			}
			if(sshCfgNode.has(CONFIG_SSH_TOKEN)) {
				this.sshToken = sshCfgNode.get(CONFIG_SSH_TOKEN).asText();
			} else {
				LOG.info("No SSH token configured, using clear text keys and password-less logins is highly discouraged.");
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
		sshWrapper = new SSHExecWrapper(sshHostname, sshPort, sshUsername, sshPrivateKey, sshToken);
	}

	final private static DateTimeFormatter snapshotTimestampFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
	public NfsVolumeProperties createPersistentVolume(Map<String, String> annotations, UUID uuid, long sizeInBytes) throws Exception {
		String command;
		int cmdReturnValue = 0;

		String zfsVolumePath = Paths.get(zfsRootPath, uuid.toString()).toString();
		String exportPath = Paths.get(nfsRootPath, uuid.toString()).toString();

		annotations.put(ANNOTATION_VOLUME_HOST, nfsHostname);
		annotations.put(ANNOTATION_VOLUME_PATH, zfsVolumePath);
		annotations.put(ANNOTATION_VOLUME_EXPORT, exportPath);
		annotations.put(ANNOTATION_PROVIDER_TYPE, ANNOTATION_PROVIDER_TYPE_NAME);

		String extraArgs = "";


		switch(quotaMode) {
			case IGNORE:
				break;
			case QUOTA:
				extraArgs = " -o quota=" + sizeInBytes;
				break;
			case RESERVE:
				extraArgs = " -o reservation=" + sizeInBytes;
				break;
			case BOTH:
				extraArgs = " -o quota=" + sizeInBytes + " -o reservation=" + sizeInBytes;
				break;
		}

		int maxBlockSize = 1048576; // If
		maxBlockSize = 131072;
		String blockSizeStr = annotations.get(ANNOTATION_BLOCKSIZE);
		if(blockSizeStr != null) {
			int blockSize = Integer.valueOf(blockSizeStr);
			// Make sure the blockSize is between 512 and maxBlockSize bytes and is a power of two
			if(blockSize >= 512 && blockSize <= maxBlockSize && ((blockSize & (blockSize - 1)) == 0)) {
				extraArgs = extraArgs + " -o recordsize=" + blockSize;
			}
		}
		String checksumMode = annotations.get(ANNOTATION_CHECKSUM_MODE);
		if(checksumMode != null) {
			switch(checksumMode.toLowerCase()) {
				case "on":
				case "off":
				case "fletcher2":
				case "fletcher4":
				case "sha256":
				case "noparity":
				case "sha512":
				case "skein":
					extraArgs = extraArgs + " -o checksum=" + checksumMode.toLowerCase();
					break;
				default:
					LOG.warn("Unexpected " + ANNOTATION_CHECKSUM_MODE + " annotation value: " + checksumMode);
					break;
			}
		}
		String compressionMode = annotations.get(ANNOTATION_COMPRESSION_MODE);
		if(compressionMode != null) {
			switch(compressionMode.toLowerCase()) {
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
					extraArgs = extraArgs + " -o compression=" + compressionMode.toLowerCase();
					break;
				default:
					LOG.warn("Unexpected " + ANNOTATION_COMPRESSION_MODE + " annotation value: " + compressionMode);
					break;
			}
		}
		String atimeMode = annotations.get(ANNOTATION_ATIME);
		if(atimeMode != null) {
			switch(atimeMode.toLowerCase()) {
				case "on":
				case "off":
					extraArgs = extraArgs + " -o atime=" + atimeMode.toLowerCase();
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
				case "off":
					extraArgs = extraArgs + " -o exec=" + execMode.toLowerCase();
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
		String snapMode = annotations.get(ANNOTATION_SNAPDIR);
		if(snapMode != null) {
			switch(snapMode.toLowerCase()) {
				case "hidden":
				case "visible":
					extraArgs = extraArgs + " -o snapdir=" + snapMode.toLowerCase();
					break;
				default:
					LOG.warn("Unexpected " + ANNOTATION_SNAPDIR + " annotation value: " + snapMode);
					break;
			}
		}
		String syncMode = annotations.get(ANNOTATION_SYNC);
		if(syncMode != null) {
			switch(syncMode.toLowerCase()) {
				case "standard":
				case "always":
				case "disabled":
					extraArgs = extraArgs + " -o sync=" + syncMode.toLowerCase();
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
					extraArgs = extraArgs + " -o casesensitivity=" + caseMode.toLowerCase();
					break;
				default:
					LOG.warn("Unexpected " + ANNOTATION_CASESENSITIVE + " annotation value: " + caseMode);
					break;
			}
		}

		// Add user attribute pointing to the name of the PV that was created for it
		extraArgs = extraArgs + " -o " + ANNOTATION_PVMANAGER_PVREF + "=" +
				annotations.get(ANNOTATION_PVMANAGER_PVREF);

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

			String zfsSnapshotName = zfsFilesystem + "@pvmanager-" + annotations.get(ANNOTATION_PVMANAGER_PVREF) + "-" + OffsetDateTime.now().format(snapshotTimestampFormatter);
			annotations.put(ANNOTATION_CLONESNAPSHOT, zfsSnapshotName);
			command = (becomeRoot ? "sudo " : "") + "zfs snapshot -o " + ANNOTATION_PVMANAGER_PVREF + "=" +
					annotations.get(ANNOTATION_PVMANAGER_PVREF) + " " + zfsSnapshotName;
			outputBuffer = new StringBuilder();

			cmdReturnValue = sshWrapper.exec(command, outputBuffer);
			if (cmdReturnValue != 0) {
				LOG.error("zfs snapshot volume failed: exit status: " + cmdReturnValue);
				LOG.error(outputBuffer.toString());
				return null;
			}

			command = (becomeRoot ? "sudo " : "") + "zfs clone " + extraArgs + " " + zfsSnapshotName + " " + zfsVolumePath;

		} else {
			command = (becomeRoot ? "sudo " : "") + "zfs create " + extraArgs + " " + zfsVolumePath;
		}

		outputBuffer = new StringBuilder();
		cmdReturnValue = sshWrapper.exec(command, outputBuffer);
		if (cmdReturnValue != 0) {
			LOG.error("zfs " + ((zfsCloneRef != null) ? "clone" : "create") + " volume failed: exit status: " + cmdReturnValue);
			LOG.error(outputBuffer.toString());
			return null;
		}

		if(unixMode != null) {
			command = String.format("%schmod %04d %s", (becomeRoot ? "sudo " : ""), unixMode, exportPath);
			outputBuffer = new StringBuilder();
			cmdReturnValue = sshWrapper.exec(command, outputBuffer);
			if (cmdReturnValue != 0) {
				LOG.error("zfs chmod exit status: " + cmdReturnValue);
				LOG.error(outputBuffer.toString());
				return null;
			}
		}

		// @TODO commented
		return new NfsVolumeProperties(nfsHostname, exportPath, false, null);
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
		xs = sshWrapper.exec(command, outputBuffer);
		if (xs != 0) {
			LOG.error("zfs destroy volume failed: exit status: " + xs + ": filesytem orphan: " + Paths.get(zfsRootPath, u.toString()).toString());
			LOG.error(outputBuffer.toString());
		}

		String sourceSnapshot = annotations.get(ANNOTATION_CLONESNAPSHOT);
		if(sourceSnapshot != null && !sourceSnapshot.isEmpty()) {
			if(!sourceSnapshot.startsWith(getZfsRootPath())) {
				LOG.error("Could not destroy snapshot, snapshot does not start with zfsRootPath: " + getZfsRootPath() + " != " + sourceSnapshot);
				return;
			}
			if(!sourceSnapshot.contains("@")) {
				LOG.error("Could not destroy snapshot, snapshot does not start with zfsRootPath: " + getZfsRootPath() + " != " + sourceSnapshot);
				return;
			}
			command = (becomeRoot ? "sudo " : "") + "zfs destroy " + sourceSnapshot;

			outputBuffer = new StringBuilder();
			xs = sshWrapper.exec(command, outputBuffer);
			if (xs != 0) {
				LOG.error("zfs destroy snapshot failed: exit status: " + xs + ": snapshot orphan: " + sourceSnapshot);
				LOG.error(outputBuffer.toString());
			}
		}

	}

	@Override
	public void close() throws Exception {
		sshWrapper.disconnect();
	}
}
