package com.shackspacehosting.engineering.openshiftpvmanager.kubernetes;

import com.openshift.restclient.IOpenShiftWatchListener;
import com.openshift.restclient.model.IResource;
import com.openshift.restclient.model.volume.IPersistentVolumeClaim;
import com.shackspacehosting.engineering.openshiftpvmanager.ModularizedStorageController;
import com.shackspacehosting.engineering.openshiftpvmanager.PVCChangeNotification;
import com.shackspacehosting.engineering.openshiftpvmanager.PVClaimWatcherService;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import java.util.List;
import java.util.Queue;

public class PVCWatchListener implements IOpenShiftWatchListener {
	private static final Logger LOG = LoggerFactory.getLogger(PVCWatchListener.class);

	private ModularizedStorageController storageController = null;

	PVClaimWatcherService parent = null;
	private Queue<PVCChangeNotification> queue;
	public PVCWatchListener(PVClaimWatcherService parent, Queue<PVCChangeNotification> queue, ModularizedStorageController storageController) {
		this.parent = parent;
		this.queue = queue;
		this.storageController = storageController;
	}

	@Override
	public void connected(List< IResource > resources) {
		for (IResource resource : resources) {
			PVCChangeNotification pvcChangeNotification = new PVCChangeNotification("ADDED", (IPersistentVolumeClaim)resource);
			if (!this.queue.contains(pvcChangeNotification)) {
				this.queue.add(pvcChangeNotification);
			} else {
				LOG.info("PVC Already exists in queue, skipping.");
			}
		}
	}

	@Override
	public void disconnected() {
		parent.pvcListenerDisconnected(this);
	}

	@Override
	public void received(IResource resource, ChangeType change) {
		PVCChangeNotification pvcChangeNotification = new PVCChangeNotification(change.getValue(), (IPersistentVolumeClaim) resource);
		if (!this.queue.contains(pvcChangeNotification)) {
			this.queue.add(pvcChangeNotification);
		} else {
			LOG.info("PVC Already exists in queue, skipping.");
		}
	}

	@Override
	public void error(Throwable err) {
		parent.pvcListenerError(this, err);
	}
}
