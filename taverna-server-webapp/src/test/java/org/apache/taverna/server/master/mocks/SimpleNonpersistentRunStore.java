package org.apache.taverna.server.master.mocks;
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.lang.ref.WeakReference;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.taverna.server.master.exceptions.NoDestroyException;
import org.apache.taverna.server.master.exceptions.UnknownRunException;
import org.apache.taverna.server.master.interfaces.Policy;
import org.apache.taverna.server.master.interfaces.RunStore;
import org.apache.taverna.server.master.interfaces.TavernaRun;
import org.apache.taverna.server.master.utils.UsernamePrincipal;

/**
 * Example of a store for Taverna Workflow Runs.
 * 
 * @author Donal Fellows
 */
public class SimpleNonpersistentRunStore implements RunStore {
	private Map<String, TavernaRun> store = new HashMap<>();
	private Object lock = new Object();

	Timer timer;
	private CleanerTask cleaner;

	/**
	 * The connection to the main policy store. Suitable for wiring up with
	 * Spring.
	 * 
	 * @param p
	 *            The policy to connect to.
	 */
	public void setPolicy(SimpleServerPolicy p) {
		p.store = this;
		cleanerIntervalUpdated(p.getCleanerInterval());
	}

	public SimpleNonpersistentRunStore() {
		timer = new Timer("SimpleNonpersistentRunStore.CleanerTimer", true);
		cleanerIntervalUpdated(300);
	}

	@Override
	protected void finalize() {
		timer.cancel();
	}

	/**
	 * Remove and destroy all runs that are expired at the moment that this
	 * method starts.
	 */
	void clean() {
		Date now = new Date();
		synchronized (lock) {
			// Use an iterator so we have access to its remove() method...
			Iterator<TavernaRun> i = store.values().iterator();
			while (i.hasNext()) {
				TavernaRun w = i.next();
				if (w.getExpiry().before(now)) {
					i.remove();
					try {
						w.destroy();
					} catch (NoDestroyException e) {
					}
				}
			}
		}
	}

	/**
	 * Reconfigure the cleaner task's call interval. This is called internally
	 * and from the Policy when the interval is set there.
	 * 
	 * @param intervalInSeconds
	 *            How long between runs of the cleaner task, in seconds.
	 */
	void cleanerIntervalUpdated(int intervalInSeconds) {
		if (cleaner != null)
			cleaner.cancel();
		cleaner = new CleanerTask(this, intervalInSeconds);
	}

	@Override
	public TavernaRun getRun(UsernamePrincipal user, Policy p, String uuid)
			throws UnknownRunException {
		synchronized (lock) {
			TavernaRun w = store.get(uuid);
			if (w == null || !p.permitAccess(user, w))
				throw new UnknownRunException();
			return w;
		}
	}

	@Override
	public TavernaRun getRun(String uuid) throws UnknownRunException {
		synchronized (lock) {
			TavernaRun w = store.get(uuid);
			if (w == null)
				throw new UnknownRunException();
			return w;
		}
	}

	@Override
	public Map<String, TavernaRun> listRuns(UsernamePrincipal user, Policy p) {
		Map<String, TavernaRun> filtered = new HashMap<>();
		synchronized (lock) {
			for (Map.Entry<String, TavernaRun> entry : store.entrySet())
				if (p.permitAccess(user, entry.getValue()))
					filtered.put(entry.getKey(), entry.getValue());
		}
		return filtered;
	}

	@Override
	public String registerRun(TavernaRun run) {
		synchronized (lock) {
			store.put(run.getId(), run);
			return run.getId();
		}
	}

	@Override
	public void unregisterRun(String uuid) {
		synchronized (lock) {
			store.remove(uuid);
		}
	}
}

class CleanerTask extends TimerTask {
	WeakReference<SimpleNonpersistentRunStore> store;

	CleanerTask(SimpleNonpersistentRunStore store, int interval) {
		this.store = new WeakReference<>(store);
		int tms = interval * 1000;
		store.timer.scheduleAtFixedRate(this, tms, tms);
	}

	@Override
	public void run() {
		SimpleNonpersistentRunStore s = store.get();
		if (s != null) {
			s.clean();
		}
	}
}