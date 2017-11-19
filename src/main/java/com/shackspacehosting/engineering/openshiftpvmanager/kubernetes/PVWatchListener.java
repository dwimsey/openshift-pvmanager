package com.shackspacehosting.engineering.openshiftpvmanager.kubernetes;

import com.openshift.internal.restclient.model.volume.property.NfsVolumeProperties;
import com.openshift.restclient.IOpenShiftWatchListener;
import com.openshift.restclient.model.IResource;
import com.openshift.restclient.model.volume.IPersistentVolume;
import com.openshift.restclient.model.volume.property.INfsVolumeProperties;
import com.openshift.restclient.model.volume.property.IPersistentVolumeProperties;
import com.shackspacehosting.engineering.openshiftpvmanager.IStorageController;
import com.shackspacehosting.engineering.openshiftpvmanager.ModularizedStorageController;
import com.shackspacehosting.engineering.openshiftpvmanager.PVChangeNotification;
import com.shackspacehosting.engineering.openshiftpvmanager.PVClaimWatcherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Queue;

public class PVWatchListener implements IOpenShiftWatchListener {
	private static final Logger LOG = LoggerFactory.getLogger(PVWatchListener.class);

	PVClaimWatcherService parent;
	private Queue<PVChangeNotification> queue;

	public PVWatchListener(PVClaimWatcherService pvClaimWatcherService, Queue<PVChangeNotification> queue) {
		this.parent = pvClaimWatcherService;
		this.queue = queue;
	}

	@Override
	public void connected(List< IResource > resources) {
		for (IResource resource : resources) {
			IPersistentVolume pv = (IPersistentVolume) resource;
			String t = resource.getNamespace();
			IPersistentVolumeProperties persistentVolumeProperties =  pv.getPersistentVolumeProperties();
			PVChangeNotification pvChangeNotification = new PVChangeNotification("INIT", pv);
			this.queue.add(pvChangeNotification);
		}
	}

	@Override
	public void disconnected() {
		parent.pvListenerDisconnected(this);
	}

	@Override
	public void received(IResource resource, ChangeType change) {
		IPersistentVolume pv = (IPersistentVolume) resource;
		PVChangeNotification pvChangeNotification = new PVChangeNotification(change.getValue(), pv);
		this.queue.add(pvChangeNotification);
	}

	@Override
	public void error(Throwable err) {
		parent.pvListenerError(this, err);
	}
}
