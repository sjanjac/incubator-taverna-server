/*
 * Copyright (C) 2010-2013 The University of Manchester
 * 
 * See the file "LICENSE" for license terms.
 */
package org.taverna.server.master.worker;

import static java.lang.Integer.parseInt;
import static java.util.UUID.randomUUID;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Required;
import org.taverna.server.master.exceptions.UnknownRunException;
import org.taverna.server.master.interfaces.Listener;
import org.taverna.server.master.interfaces.Policy;
import org.taverna.server.master.interfaces.RunStore;
import org.taverna.server.master.interfaces.TavernaRun;
import org.taverna.server.master.notification.NotificationEngine;
import org.taverna.server.master.notification.NotificationEngine.Message;
import org.taverna.server.master.utils.UsernamePrincipal;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * The main facade bean that interfaces to the database of runs.
 * 
 * @author Donal Fellows
 */
public class RunDatabase implements RunStore, RunDBSupport {
	private Log log = LogFactory.getLog("Taverna.Server.Worker.RunDB");
	RunDatabaseDAO dao;
	CompletionNotifier backupNotifier;
	Map<String, CompletionNotifier> typedNotifiers;
	private NotificationEngine notificationEngine;
	@Autowired
	private FactoryBean factory;
	private Map<String, TavernaRun> cache;
	private volatile boolean cacheBeingCleaned;

	@Override
	@Required
	public void setNotifier(CompletionNotifier n) {
		backupNotifier = n;
	}

	public void setTypeNotifiers(List<CompletionNotifier> notifiers) {
		typedNotifiers = new HashMap<String, CompletionNotifier>();
		for (CompletionNotifier n : notifiers)
			typedNotifiers.put(n.getName(), n);
	}

	@Required
	@Override
	public void setNotificationEngine(NotificationEngine notificationEngine) {
		this.notificationEngine = notificationEngine;
	}

	@Required
	public void setDao(RunDatabaseDAO dao) {
		this.dao = dao;
	}

	@Override
	public void checkForFinishNow() {
		for (RemoteRunDelegate rrd : dao.getNotifiable())
			for (Listener l : rrd.getListeners())
				if (l.getName().equals("io")) {
					try {
						notifyFinished(rrd.id, l, rrd);
					} catch (Exception e) {
						log.warn("failed to do notification of completion", e);
					}
					break;
				}
	}

	@Override
	public void cleanNow() {
		try {
			cacheBeingCleaned = true;
			List<String> cleaned;
			try {
				cleaned = dao.doClean();
			} catch (Exception e) {
				log.warn("failure during deletion of expired runs", e);
				return;
			}
			synchronized (cache) {
				for (String id : cleaned)
					cache.remove(id);
			}
		} finally {
			cacheBeingCleaned = false;
		}
	}

	@Override
	public int countRuns() {
		return dao.countRuns();
	}

	@Override
	public void flushToDisk(RemoteRunDelegate run) {
		try {
			dao.flushToDisk(run);
		} catch (IOException e) {
			throw new RuntimeException(
					"unexpected problem when persisting run record in database",
					e);
		}
	}

	@Override
	public RemoteRunDelegate pickArbitraryRun() throws Exception {
		return dao.pickArbitraryRun();
	}

	@Override
	public List<String> listRunNames() {
		return dao.listRunNames();
	}

	@Nullable
	private TavernaRun get(String uuid) {
		TavernaRun run = null;
		if (!cacheBeingCleaned)
			synchronized (cache) {
				run = cache.get(uuid);
			}
		if (run == null)
			run = dao.get(uuid);
		return run;
	}

	@Override
	public TavernaRun getRun(UsernamePrincipal user, Policy p, String uuid)
			throws UnknownRunException {
		// Check first to see if the 'uuid' actually looks like a UUID; if
		// not, throw it out immediately without logging an exception.
		try {
			UUID.fromString(uuid);
		} catch (IllegalArgumentException e) {
			log.debug("run ID does not look like UUID; rejecting...");
			throw new UnknownRunException();
		}
		TavernaRun run = get(uuid);
		if (run != null && (user == null || p.permitAccess(user, run)))
			return run;
		throw new UnknownRunException();
	}

	@Override
	public TavernaRun getRun(String uuid) throws UnknownRunException {
		TavernaRun run = get(uuid);
		if (run != null)
			return run;
		throw new UnknownRunException();
	}

	@Override
	public Map<String, TavernaRun> listRuns(UsernamePrincipal user, Policy p) {
		return dao.listRuns(user, p);
	}

	private void logLength(String message, Object obj) {
		if (!log.isDebugEnabled())
			return;
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ObjectOutputStream oos = new ObjectOutputStream(baos);
			oos.writeObject(obj);
			oos.close();
			log.debug(message + ": " + baos.size());
		} catch (Exception e) {
			log.warn("oops", e);
		}
	}

	@Override
	public String registerRun(TavernaRun run) {
		if (!(run instanceof RemoteRunDelegate))
			throw new IllegalArgumentException(
					"run must be created by localworker package");
		RemoteRunDelegate rrd = (RemoteRunDelegate) run;
		if (rrd.id == null)
			rrd.id = randomUUID().toString();
		logLength("RemoteRunDelegate serialized length", rrd);
		try {
			dao.persistRun(rrd);
		} catch (IOException e) {
			throw new RuntimeException(
					"unexpected problem when persisting run record in database",
					e);
		}
		synchronized (cache) {
			cache.put(rrd.getId(), run);
		}
		return rrd.getId();
	}

	@Override
	public void unregisterRun(String uuid) {
		try {
			if (dao.unpersistRun(uuid))
				synchronized (cache) {
					cache.remove(uuid);
				}
		} catch (RuntimeException e) {
			log.debug("problem persisting the deletion of the run " + uuid, e);
		}
	}

	/**
	 * Process the event that a run has finished.
	 * 
	 * @param name
	 *            The name of the run.
	 * @param io
	 *            The io listener of the run (used to get information about the
	 *            run).
	 * @param run
	 *            The handle to the run.
	 * @throws Exception
	 *             If anything goes wrong.
	 */
	private void notifyFinished(final String name, Listener io,
			final RemoteRunDelegate run) throws Exception {
		String to = io.getProperty("notificationAddress");
		final int code;
		try {
			code = parseInt(io.getProperty("exitcode"));
		} catch (NumberFormatException nfe) {
			// Ignore; not much we can do here...
			return;
		}

		notificationEngine.dispatchMessage(run, to, new Message() {
			private CompletionNotifier getNotifier(String type) {
				CompletionNotifier n = typedNotifiers.get(type);
				if (n == null)
					n = backupNotifier;
				return n;
			}

			@Override
			public String getContent(String type) {
				return getNotifier(type).makeCompletionMessage(name, run, code);
			}

			@Override
			public String getTitle(String type) {
				return getNotifier(type).makeMessageSubject(name, run, code);
			}
		});
	}

	@Override
	public FactoryBean getFactory() {
		return factory;
	}
}
