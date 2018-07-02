package com.shackspacehosting.engineering.openshiftpvmanager.storage.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.shackspacehosting.engineering.openshiftpvmanager.SSHExecWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import static com.shackspacehosting.engineering.openshiftpvmanager.PVClaimManagerService.*;

public class NFS implements IStorageManagementProvider, AutoCloseable {
	private static final Logger LOG = LoggerFactory.getLogger(NFS.class);

	private SSHExecWrapper sshWrapper;

	private boolean becomeRoot;


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

	public NFS() {
	}


	public NFS(JsonNode cfgNode) throws IOException {
		JsonNode nfsCfgNode = cfgNode.get("nfs");
		if (nfsCfgNode != null) {
			this.nfsHostname = nfsCfgNode.get("hostname").asText();
			this.nfsRootPath = nfsCfgNode.get("exportRoot").asText();
		}

		JsonNode sshCfgNode = cfgNode.get("ssh");
		if(sshCfgNode != null) {
			this.sshHostname = sshCfgNode.get("hostname").asText();
			this.sshPort = sshCfgNode.get("port").asInt(23);
			this.sshUsername = sshCfgNode.get("identity").asText();
			if(sshCfgNode.has("privateKeyFile")) {
				this.sshPrivateKey = sshCfgNode.get("privateKeyFile").asText();
				//this.sshPrivateKey = new String(Files.readAllBytes(Paths.get(sshCfgNode.get("privateKeyFile").asText())));
			} else if(sshCfgNode.has("privateKey")) {
				this.sshPrivateKey = sshCfgNode.get("privateKey").asText();
			}
			this.sshToken = sshCfgNode.get("token").asText();
		}
		JsonNode zfsCfgNode = cfgNode.get("zfs");
		if(zfsCfgNode != null) {
			this.zfsRootPath = zfsCfgNode.get("rootPath").asText();
			this.becomeRoot = zfsCfgNode.get("becomeRoot").asBoolean(false);
		}

		this.init();
	}

	public NFS(String nfsHostname, String nfsRoot, String zfsRoot, boolean become, String sshHostname, int sshPort, String sshKeyfile, String sshToken) {
		this.nfsHostname = nfsHostname;
		this.nfsRootPath = nfsRoot;
		this.zfsRootPath = zfsRoot;
		this.becomeRoot = become;
		this.sshHostname = sshHostname;
		this.sshPort = sshPort;
		this.sshPrivateKey = sshKeyfile;
		this.sshToken = sshToken;

		this.init();
	}


	public void init() {
		sshWrapper = new SSHExecWrapper(sshHostname, sshPort, sshUsername, sshPrivateKey, sshToken);
	}
	final public static String ANNOTATION_VOLUME_HOST = ANNOTATION_BASE + "nfs-host";
	final public static String ANNOTATION_VOLUME_PATH = ANNOTATION_BASE + "nfs-path";
	final public static String ANNOTATION_VOLUME_EXPORT = ANNOTATION_BASE + "nfs-export";
	final public static String ANNOTATION_PROVIDER_TYPE_NAME = "nfs";

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


		int maxBlockSize = 1048576; // If
		maxBlockSize = 131072;
		String blockSizeStr = annotations.get(ANNOTATION_BLOCKSIZE);
		if(blockSizeStr != null) {
			int blockSize = Integer.valueOf(blockSizeStr);
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
			}
		}
		String atimeMode = annotations.get(ANNOTATION_ATIME);
		if(atimeMode != null) {
			switch(atimeMode.toLowerCase()) {
				case "on":
				case "off":
					extraArgs = extraArgs + " -o atime=" + atimeMode.toLowerCase();
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
			}
		}
		String snapMode = annotations.get(ANNOTATION_SNAPDIR);
		if(snapMode != null) {
			switch(snapMode.toLowerCase()) {
				case "hidden":
				case "visible":
					extraArgs = extraArgs + " -o snapdir=" + snapMode.toLowerCase();
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
			}
		}

			command = (becomeRoot ? "sudo " : "") + "zfs create -o quota=" + sizeInBytes + extraArgs + " " + zfsVolumePath;


		StringBuilder outputBuffer = new StringBuilder();

		cmdReturnValue = sshWrapper.exec(command, outputBuffer);
		if (cmdReturnValue != 0) {
			LOG.error("Exit status: " + cmdReturnValue);
			LOG.error(outputBuffer.toString());
			return null;
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
		String command = (becomeRoot ? "sudo " : "") + "zfs destroy " + Paths.get(zfsRootPath, u.toString()).toString();

		StringBuilder outputBuffer = new StringBuilder();

		int xs = 0;
		xs = sshWrapper.exec(command, outputBuffer);
		if (xs != 0) {
			LOG.error("Exit status: " + xs);
			LOG.error(outputBuffer.toString());
			return;
		}
	}

	@Override
	public void close() throws Exception {
		sshWrapper.disconnect();
	}
}
