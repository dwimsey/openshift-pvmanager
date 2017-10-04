package com.shackspacehosting.engineering.openshiftpvmanager;

import com.jcraft.jsch.JSchException;
import com.openshift.internal.restclient.model.volume.property.NfsVolumeProperties;
import com.openshift.restclient.ResourceKind;
import com.openshift.restclient.model.volume.IPersistentVolume;
import com.openshift.restclient.model.volume.property.IPersistentVolumeProperties;
import com.openshift.restclient.utils.MemoryUnit;

import java.io.IOException;
import java.util.UUID;

/**
 * Created by dwimsey on 10/4/17.
 */
public class ZFSStorageController {
	private SSHExecWrapper sshWrapper;

	private String zfsRoot;
	private boolean becomeRoot;
	private String nfsHostname;
	private String nfsRoot;

	public ZFSStorageController(String nfsHostname, String nfsRoot, String zfsRoot, boolean become, String sshHostname, int sshPort, String sshUsername, String sshKeyfile, String sshKeySecret) {
		this.nfsHostname = nfsHostname;
		this.nfsRoot = nfsRoot;
		this.zfsRoot = zfsRoot;
		this.becomeRoot = become;
		sshWrapper = new SSHExecWrapper(sshHostname, sshPort, sshUsername, sshKeyfile, sshKeySecret);
	}

	public void init() throws JSchException {
		sshWrapper.connect();
	}

	public void shutdown() {
		sshWrapper.disconnect();
	}

	public IPersistentVolumeProperties createPersistentVolume(long sizeInUnits, MemoryUnit unitSize) throws IOException, JSchException {
		UUID uuid = UUID.randomUUID();
		String command = (becomeRoot ? "sudo " : "") + "zfs create " + zfsRoot + "/" + uuid.toString();

		StringBuilder outputBuffer = new StringBuilder();

		int xs = 0;
		xs = sshWrapper.exec(command, outputBuffer);
		if (xs != 0) {
			System.err.println("Exit status: " + xs);
			System.err.println(outputBuffer.toString());
			return null;
		}
		return new NfsVolumeProperties(nfsHostname, nfsRoot + "/" + uuid.toString(), false);
	}
}
