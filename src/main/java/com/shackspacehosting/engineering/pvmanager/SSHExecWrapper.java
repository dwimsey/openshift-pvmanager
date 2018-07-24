package com.shackspacehosting.engineering.pvmanager;

import com.jcraft.jsch.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * Created by dwimsey on 10/4/17.
 */
public class SSHExecWrapper {
	private static final Logger LOG = LoggerFactory.getLogger(SSHExecWrapper.class);

	private String sshHostname;
	private int sshPort;
	private String sshUsername;
	private String sshKeyfile;
	private String sshKeySecret;
	private Session session = null;

	public SSHExecWrapper(String sshHostname, int sshPort, String sshUsername, String sshKeyfile, String sshKeySecret) {
		this.sshHostname = sshHostname;
		this.sshPort = sshPort;
		this.sshUsername = sshUsername;
		this.sshKeyfile = sshKeyfile;
		this.sshKeySecret = sshKeySecret;
	}


	public void connect() throws JSchException {
		JSch jsch = new JSch();
		try {
			if(sshKeyfile != null && sshKeySecret != null) {
				jsch.addIdentity(sshKeyfile, sshKeySecret);
			} else if(sshKeyfile != null && sshKeySecret == null) {
				jsch.addIdentity(sshKeyfile);
			}
		} catch (JSchException e) {
			LOG.error("SSH identity configuration error: {}", e);
			throw e;
		}

		session = jsch.getSession(sshUsername, sshHostname, sshPort);
		session.setConfig("StrictHostKeyChecking", "no");
		try {
			session.connect(30000);
		} catch(JSchException e) {
			session = null;
			throw e;
		}
	}

	public int exec(String command, StringBuilder outputBuffer) throws JSchException, IOException {
		if(session == null) {
			this.connect();
		}

		try {
			Channel channel;

			channel = session.openChannel("exec");

			((ChannelExec) channel).setCommand(command);
			InputStream commandOutput = channel.getInputStream();
			InputStream commandErrOutput = ((ChannelExec) channel).getErrStream();
			try {
				channel.connect();
			} catch(JSchException e) {
				channel.connect();
			}
			int readByte = commandOutput.read();

			while (readByte != 0xffffffff) {
				outputBuffer.append((char) readByte);
				readByte = commandOutput.read();
			}

			readByte = commandErrOutput.read();

			while (readByte != 0xffffffff) {
				outputBuffer.append((char) readByte);
				readByte = commandErrOutput.read();
			}

			channel.disconnect();
			return channel.getExitStatus();
		} catch (Exception e) {
			disconnect();
			session = null;
			throw e;
		}
	}

	public void disconnect() {
		if (session != null) {
			if (session.isConnected()) {
				session.disconnect();
			}
		}
	}
}
