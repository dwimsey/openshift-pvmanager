package com.shackspacehosting.engineering.openshiftpvmanager.kubernetes;

import com.openshift.restclient.IOpenShiftWatchListener;
import com.openshift.restclient.model.IResource;
import com.openshift.restclient.model.volume.IPersistentVolume;
import com.shackspacehosting.engineering.openshiftpvmanager.PVClaimWatcherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class PVWatchListener implements IOpenShiftWatchListener {
	private static final Logger LOG = LoggerFactory.getLogger(PVWatchListener.class);

	PVClaimWatcherService parent;

	public PVWatchListener(PVClaimWatcherService pvClaimWatcherService) {
		this.parent = pvClaimWatcherService;
	}

	@Override
	public void connected(List< IResource > resources) {
		for (IResource resource : resources) {
			IPersistentVolume pvc = (IPersistentVolume) resource;
//			LOG.info(" Watching resource type: {}", pvc.getName());
//			LOG.info("           Access Modes: {}", pvc.getAccessModes());
		}
	}

	@Override
	public void disconnected() {
		parent.pvListenerDisconnected(this);
	}

	@Override
	public void received(IResource resource, ChangeType change) {
		IPersistentVolume pvc = (IPersistentVolume) resource;
		LOG.info(" Event for resource: {}", resource.getName());
		LOG.info("         Event type: {}", change.getValue());
		LOG.info("       Access Modes: {}", pvc.getAccessModes());
	}

	@Override
	public void error(Throwable err) {
		parent.pvListenerError(this, err);
	}
}
