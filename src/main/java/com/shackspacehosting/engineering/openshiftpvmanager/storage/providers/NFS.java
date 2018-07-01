package com.shackspacehosting.engineering.openshiftpvmanager.storage.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.shackspacehosting.engineering.openshiftpvmanager.SSHExecWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

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

	public NfsVolumeProperties createPersistentVolume(Map<String, String> annotations, UUID uuid, long sizeInBytes) throws Exception {
		String suffixChar = null;

		String command = (becomeRoot ? "sudo " : "") + "zfs create -o quota=" + sizeInBytes + " " + zfsRootPath + "/" + uuid.toString();

		StringBuilder outputBuffer = new StringBuilder();

		int xs = 0;
		xs = sshWrapper.exec(command, outputBuffer);
		if (xs != 0) {
			LOG.error("Exit status: " + xs);
			LOG.error(outputBuffer.toString());
			return null;
		}
		// @TODO commented
		return new NfsVolumeProperties(nfsHostname, nfsRootPath + "/" + uuid.toString(), false);
	}

	@Override
	public void close() throws Exception {
		sshWrapper.disconnect();
	}
}
