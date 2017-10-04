package com.shackspacehosting.engineering.openshiftpvmanager;

import com.jcraft.jsch.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;

/**
 * Created by dwimsey on 10/4/17.
 */
public class SSHExecWrapper {
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
			jsch.addIdentity(sshKeyfile, sshKeySecret.getBytes("UTF-8"));
		} catch (JSchException e) {
			e.printStackTrace();
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}

		session = jsch.getSession(sshUsername, sshHostname, sshPort);
		session.setConfig("StrictHostKeyChecking", "no");
		session.connect(30000);
	}
/*
		String command = "sudo zfs create " + zfsRoot + "/" + vName;
		StringBuilder outputBuffer = new StringBuilder();

		try {
			Channel channel = session.openChannel("exec");
			((ChannelExec) channel).setCommand(command);
			InputStream commandOutput = channel.getInputStream();
			channel.connect();
			int readByte = commandOutput.read();

			while (readByte != 0xffffffff) {
				outputBuffer.append((char) readByte);
				readByte = commandOutput.read();
			}

			channel.disconnect();
			int xs = channel.getExitStatus();
			xs = 0;
			if (xs != 0) {
				System.err.println("Exit status: " + xs);
				System.err.println(outputBuffer.toString());
			} else {
				// Create the pv to nfs mapping

			}
		} catch (IOException ioX) {
			System.err.println("Exception: " + ioX.getMessage());
		} catch (JSchException jschX) {
			System.err.println("Exception: " + jschX.getMessage());
		}



	}
*/

	public int exec(String command, StringBuilder outputBuffer) throws JSchException, IOException {
		Channel channel = session.openChannel("exec");
		((ChannelExec) channel).setCommand(command);
		InputStream commandOutput = channel.getInputStream();
		channel.connect();
		int readByte = commandOutput.read();

		while (readByte != 0xffffffff) {
			outputBuffer.append((char) readByte);
			readByte = commandOutput.read();
		}

		channel.disconnect();
		return channel.getExitStatus();
	}

	public void disconnect() {
		session.disconnect();
	}
}
