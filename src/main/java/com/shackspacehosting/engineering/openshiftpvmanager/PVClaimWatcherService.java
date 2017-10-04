package com.shackspacehosting.engineering.openshiftpvmanager;

import com.openshift.internal.restclient.model.volume.property.NfsVolumeProperties;
import com.openshift.restclient.ResourceKind;
import com.openshift.restclient.capability.CapabilityVisitor;
import com.openshift.restclient.model.*;
import com.openshift.restclient.model.build.IBuildConfigBuilder;
import com.openshift.restclient.model.volume.IPersistentVolume;
import com.openshift.restclient.model.volume.IPersistentVolumeClaim;
import com.openshift.restclient.model.volume.property.INfsVolumeProperties;
import com.openshift.restclient.model.volume.property.IPersistentVolumeProperties;
import com.openshift.restclient.utils.MemoryUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.InitializingBean;
import com.openshift.restclient.ClientBuilder;
import com.openshift.restclient.IClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jcraft.jsch.*;

@Component
public class PVClaimWatcherService implements InitializingBean, DisposableBean {

	@Value("${openshift.url}")
	private String openShiftUrl;

	@Value("${openshift.username}")
	private String openShiftUsername;

	@Value("${openshift.secret}")
	private String openShiftPassword;

	@Value("${openshift.pollsleepms}")
	private int watcherPollSleepTime;


	@Value("${nfs.hostname}")
	private String nfsHostname;

	@Value("${nfs.root}")
	private String nfsRoot;

	@Value("${ssh.hostname}")
	private String sshHostname;

	@Value("${ssh.port}")
	private int sshPort;

	@Value("${ssh.keyfile}")
	private String sshKeyfile;

	@Value("${ssh.username}")
	private String sshUsername;

	@Value("${ssh.keysecret}")
	private String sshKeySecret;

	@Value("${zfs.root}")
	private String zfsRoot;

	@Override
	public void afterPropertiesSet() throws Exception {
		startPVCWatcherService();
	}

	private boolean beanShouldStop = false;
	private void startPVCWatcherService() {
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				Pattern p = Pattern.compile("([0-9]+)([A-Za-z]+)");

				while(!beanShouldStop) {
					try {
						IClient client = new ClientBuilder(openShiftUrl)
								.withUserName(openShiftUsername)
								.withPassword(openShiftPassword)
								.build();

						while(!beanShouldStop) {
							List<IProject> projectList = client.list(ResourceKind.PROJECT);
							for (IProject rItem : projectList) {

								List<IPersistentVolumeClaim> pvcList = client.list(ResourceKind.PVC, rItem.getNamespace());
								for (IPersistentVolumeClaim pvc : pvcList) {
									switch (pvc.getStatus().toLowerCase()) {
										case "pending":
										case "lost":
											String requestedStorageString = pvc.getRequestedStorage();
											Matcher m = p.matcher(requestedStorageString);
											boolean b = m.matches();
											String sSize = m.group(1);
											String mUnit = m.group(2);
											System.err.println("Found pvc: " + pvc.getName() + "( " + pvc.getVolumeName() + ") " + pvc.getRequestedStorage());


											String vName = pvc.getNamespace() + "-" + pvc.getName();
											JSch jsch = new JSch();
											try {
												jsch.addIdentity(sshKeyfile, sshKeySecret.getBytes("UTF-8"));
											} catch (JSchException e) {
												e.printStackTrace();
											} catch (UnsupportedEncodingException e) {
												e.printStackTrace();
											}
											Session session = null;
											try {
												session = jsch.getSession(sshUsername, sshHostname, sshPort);
												session.setConfig("StrictHostKeyChecking", "no");
												session.connect(30000);   // making a connection with timeout.
											} catch (JSchException e) {
												e.printStackTrace();
											}

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

													IPersistentVolume service = (IPersistentVolume)client.getResourceFactory().stub(ResourceKind.PERSISTENT_VOLUME, vName);
													Set<String> accessModesSet = pvc.getAccessModes();
													String[] accessModes = new String[accessModesSet.size()];
													accessModesSet.toArray(accessModes);
													service.setAccessModes(accessModes);
													service.setReclaimPolicy("Recycle");
													service.setCapacity(Long.valueOf(sSize), MemoryUnit.valueOf(mUnit));
													String dirName =  Paths.get(nfsRoot, vName).toString();
													service.setPersistentVolumeProperties(new NfsVolumeProperties(nfsHostname, dirName, false));

													try {
														service = client.create(service);
													} catch (Exception e) {
														System.err.println("Exception: " + e.getMessage());
													}
												}
											} catch (IOException ioX) {
												System.err.println("Exception: " + ioX.getMessage());
											} catch (JSchException jschX) {
												System.err.println("Exception: " + jschX.getMessage());
											}


											session.disconnect();
											break;
										case "bound":
											break;
										default:
											System.err.println("Found pvc: " + pvc.getName() + "( " + pvc.getVolumeName() + ") " + pvc.getNamespace() + ": unknown state: " + pvc.getStatus());
									}
								}
							}
							try {
								Thread.sleep(watcherPollSleepTime);
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
						}
					} catch(Exception e) {
						System.err.println("Crash crash crash: " + e.getMessage());
					}
				}
				System.err.println("Shutting down Persistent Volume Claim Watcher.");
			}
		});
		t.start();
	}

	@Override
	public void destroy() throws Exception {
		beanShouldStop = true;
	}
}
