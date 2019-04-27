/*
 *    This file is part of mlDHT.
 * 
 *    mlDHT is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 2 of the License, or
 *    (at your option) any later version.
 * 
 *    mlDHT is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 * 
 *    You should have received a copy of the GNU General Public License
 *    along with mlDHT.  If not, see <http://www.gnu.org/licenses/>.
 */
package lbms.plugins.mldht.kad;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import lbms.plugins.mldht.DHTConfiguration;
import lbms.plugins.mldht.kad.Node.RoutingTableEntry;
import lbms.plugins.mldht.kad.messages.AbstractLookupRequest;
import lbms.plugins.mldht.kad.messages.AnnounceRequest;
import lbms.plugins.mldht.kad.messages.AnnounceResponse;
import lbms.plugins.mldht.kad.messages.ErrorMessage;
import lbms.plugins.mldht.kad.messages.ErrorMessage.ErrorCode;
import lbms.plugins.mldht.kad.messages.FindNodeRequest;
import lbms.plugins.mldht.kad.messages.FindNodeResponse;
import lbms.plugins.mldht.kad.messages.GetPeersRequest;
import lbms.plugins.mldht.kad.messages.GetPeersResponse;
import lbms.plugins.mldht.kad.messages.MessageBase;
import lbms.plugins.mldht.kad.messages.PingRequest;
import lbms.plugins.mldht.kad.messages.PingResponse;
import lbms.plugins.mldht.kad.messages.UnknownTypeResponse;
import lbms.plugins.mldht.kad.tasks.AnnounceTask;
import lbms.plugins.mldht.kad.tasks.NodeLookup;
import lbms.plugins.mldht.kad.tasks.PeerLookupTask;
import lbms.plugins.mldht.kad.tasks.PingRefreshTask;
import lbms.plugins.mldht.kad.tasks.Task;
import lbms.plugins.mldht.kad.tasks.TaskListener;
import lbms.plugins.mldht.kad.tasks.TaskManager;
import lbms.plugins.mldht.kad.utils.AddressUtils;
import lbms.plugins.mldht.kad.utils.ByteWrapper;
import lbms.plugins.mldht.kad.utils.PopulationEstimator;
import lbms.plugins.mldht.utils.NIOConnectionManager;

/**
 * @author Damokles
 * 
 */
public class DHT implements DHTBase {
	
	private static String TAG = DHT.class.getSimpleName();
	
	public static enum DHTtype {
		IPV4_DHT("IPv4",20+4+2, 4+2, Inet4Address.class,20+8, 1400),
		IPV6_DHT("IPv6",20+16+2, 16+2, Inet6Address.class,40+8, 1200);
		
		public final int							HEADER_LENGTH;
		public final int 							NODES_ENTRY_LENGTH;
		public final int							ADDRESS_ENTRY_LENGTH;
		public final Class<? extends InetAddress>	PREFERRED_ADDRESS_TYPE;
		public final int							MAX_PACKET_SIZE;
		public final String 						shortName;
		private DHTtype(String shortName, int nodeslength, int addresslength, Class<? extends InetAddress> addresstype, int header, int maxSize) {
			this.shortName = shortName;
			this.NODES_ENTRY_LENGTH = nodeslength;
			this.PREFERRED_ADDRESS_TYPE = addresstype;
			this.ADDRESS_ENTRY_LENGTH = addresslength;
			this.HEADER_LENGTH = header;
			this.MAX_PACKET_SIZE = maxSize;
		}

	}


	private static DHTLogger				logger;
	private static LogLevel					logLevel	= LogLevel.Info;

	private static ScheduledThreadPoolExecutor	scheduler;
	private static ThreadGroup					executorGroup;
	
	static {
		executorGroup = new ThreadGroup("mlDHT");
		int threads = Math.max(Runtime.getRuntime().availableProcessors(),2);
		/*scheduler = new ScheduledThreadPoolExecutor(threads, (ThreadFactory) r -> {
			Thread t = new Thread(executorGroup, r, "mlDHT Scheduler");
			
			t.setUncaughtExceptionHandler((t1, e) -> DHT.log(e, LogLevel.Error));
			t.setDaemon(true);
			return t;
		});*/
		
		scheduler = new ScheduledThreadPoolExecutor(threads, new ThreadFactory() {
			public Thread newThread (Runnable r) {
				Thread t = new Thread(executorGroup, r, "mlDHT Executor");
				t.setUncaughtExceptionHandler(new UncaughtExceptionHandler() {
					@Override
					public void uncaughtException(Thread t, Throwable e) {
						DHT.log(e, LogLevel.Error);
					}
				});
				t.setDaemon(true);
				return t;
			}
		});
		
		scheduler.setCorePoolSize(threads);
		scheduler.setMaximumPoolSize(threads*2);
		scheduler.setKeepAliveTime(20, TimeUnit.SECONDS);
		scheduler.allowCoreThreadTimeOut(true);

		logger = new DHTLogger() {
			public void log (String message, LogLevel l) {
				System.out.println(message);
			};

			/*
			 * (non-Javadoc)
			 * 
			 * @see lbms.plugins.mldht.kad.DHTLogger#log(java.lang.Exception)
			 */
			public void log (Throwable e, LogLevel l) {
				e.printStackTrace();
			}
		};
	}

	private boolean							running;

	private boolean							bootstrapping;
	private long							lastBootstrap;

	DHTConfiguration						config;
	private Node							node;
	private RPCServerManager				serverManager;
	private Database						db;
	private TaskManager						tman;
	private File							tableFile;
	private boolean							useRouterBootstrapping;

	private List<DHTStatsListener>			statsListeners;
	private List<DHTStatusListener>			statusListeners;
	private List<DHTIndexingListener>		indexingListeners;
	private DHTStats						stats;
	private DHTStatus						status;
	private PopulationEstimator				estimator;
	private AnnounceNodeCache				cache;
	private NIOConnectionManager			connectionManager;
	
	RPCStats								serverStats;

	private final DHTtype					type;
	private List<ScheduledFuture<?>>		scheduledActions = new ArrayList<ScheduledFuture<?>>();
	
	
	static Map<DHTtype,DHT> dhts;


	public synchronized static Map<DHTtype, DHT> createDHTs() {
		if (dhts == null) {
			dhts = new EnumMap<DHTtype,DHT>(DHTtype.class);
			
			dhts.put(DHTtype.IPV4_DHT, new DHT(DHTtype.IPV4_DHT));
			dhts.put(DHTtype.IPV6_DHT, new DHT(DHTtype.IPV6_DHT));
		}
		
		return dhts;
	}
	
	public static DHT getDHT(DHTtype type) {
		return dhts.get(type);
	}

	private DHT(DHTtype type) {
		this.type = type;
		
		stats = new DHTStats();
		status = DHTStatus.Stopped;
		statsListeners = new ArrayList<DHTStatsListener>(2);
		statusListeners = new ArrayList<DHTStatusListener>(2);
		indexingListeners = new ArrayList<DHTIndexingListener>();
		estimator = new PopulationEstimator();
	}
	
	public static interface IncomingMessageListener {
		void received(DHT dht, MessageBase msg);
	}
	
	private List<IncomingMessageListener> incomingMessageListeners = new ArrayList<>();
	
	public void addIncomingMessageListener(IncomingMessageListener l) {
		incomingMessageListeners.add(l);
	}
	
	void incomingMessage(MessageBase msg) {
		//incomingMessageListeners.forEach(e -> e.received(this, msg));
		for (int i = 0; i < incomingMessageListeners.size(); i++) {
			IncomingMessageListener e = incomingMessageListeners.get(i);
			e.received(this, msg);
		}
	}
	
	public void ping(PingRequest r) {
		if (!isRunning()) {
			return;
		}

		// ignore requests we get from ourself
		if (node.isLocalId(r.getID())) {
			return;
		}

		PingResponse rsp = new PingResponse(r.getMTID());
		rsp.setDestination(r.getOrigin());
		r.getServer().sendMessage(rsp);
		node.recieved(r);
	}

	public void findNode(AbstractLookupRequest r) {
		
		if (!isRunning()) {
			return;
		}

		// ignore requests we get from ourself
		if (node.isLocalId(r.getID())) {
			return;
		}

		node.recieved(r);
		// find the K closest nodes and pack them

		KClosestNodesSearch kns4 = null;
		KClosestNodesSearch kns6 = null;
		
		// add our local address of the respective DHT for cross-seeding, but not for local requests
		if (r.doesWant4()) {
			kns4 = new KClosestNodesSearch(r.getTarget(), DHTConstants.MAX_ENTRIES_PER_BUCKET, getDHT(DHTtype.IPV4_DHT));
			kns4.fill(DHTtype.IPV4_DHT != type);
		}
		if (r.doesWant6()) {
			kns6 = new KClosestNodesSearch(r.getTarget(), DHTConstants.MAX_ENTRIES_PER_BUCKET, getDHT(DHTtype.IPV6_DHT));
			kns6.fill(DHTtype.IPV6_DHT != type);
		}
		
		FindNodeResponse response;
		if (r instanceof FindNodeRequest)
			response = new FindNodeResponse(r.getMTID(), kns4 != null ? kns4.pack() : null,kns6 != null ? kns6.pack() : null);
		else
			response = new UnknownTypeResponse(r.getMTID(), kns4 != null ? kns4.pack() : null,kns6 != null ? kns6.pack() : null);
		response.setDestination(r.getOrigin());
		r.getServer().sendMessage(response);
	}

	public void response(MessageBase r) {
		if (!isRunning()) {
			return;
		}
		node.recieved(r);
	}

	public void getPeers(GetPeersRequest r) {
		if (!isRunning()) {
			return;
		}

		// ignore requests we get from ourself
		if (node.isLocalId(r.getID())) {
			return;
		}

		node.recieved(r);
		
		BloomFilterBEP33 peerFilter = r.isScrape() ? db.createScrapeFilter(r.getInfoHash(), false) : null;
		BloomFilterBEP33 seedFilter = r.isScrape() ? db.createScrapeFilter(r.getInfoHash(), true) : null;
		
		boolean v6 = Inet6Address.class.isAssignableFrom(type.PREFERRED_ADDRESS_TYPE);
		
		boolean heavyWeight = peerFilter != null;
		
		int valuesTargetLength = 50;
		// scrape filter gobble up a lot of space, restrict list sizes
		if (heavyWeight)
			valuesTargetLength =  v6 ? 15 : 30;
		
		List<DBItem> dbl = db.sample(r.getInfoHash(), valuesTargetLength,type, r.isNoSeeds());

		for (DHTIndexingListener listener : indexingListeners) {
			List<PeerAddressDBItem> toAdd = listener.incomingPeersRequest(r.getInfoHash(), r.getOrigin().getAddress(), r.getID());
			if (dbl == null && !toAdd.isEmpty())
				dbl = new ArrayList<DBItem>();
			if (dbl != null && !toAdd.isEmpty())
				dbl.addAll(toAdd);
		}
		
		// generate a token
		ByteWrapper token = null;
		if (db.insertForKeyAllowed(r.getInfoHash()))
			token = db.genToken(r.getOrigin().getAddress(), r.getOrigin().getPort(), r.getInfoHash());

		KClosestNodesSearch kns4 = null;
		KClosestNodesSearch kns6 = null;
		
		if (r.doesWant4()) {
			kns4 = new KClosestNodesSearch(r.getTarget(), DHTConstants.MAX_ENTRIES_PER_BUCKET, getDHT(DHTtype.IPV4_DHT));
			// add our local address of the respective DHT for cross-seeding, but not for local requests
			kns4.fill(DHTtype.IPV4_DHT != type);
		}
		
		if (r.doesWant6()) {
			
			int targetNodesCount = DHTConstants.MAX_ENTRIES_PER_BUCKET;
			// can't embed many nodes in v6 responses with filters
			if (v6 && peerFilter != null)
				targetNodesCount = Math.min(5, targetNodesCount);
			
			kns6 = new KClosestNodesSearch(r.getTarget(), targetNodesCount, getDHT(DHTtype.IPV6_DHT));
			kns6.fill(DHTtype.IPV6_DHT != type);
		}
		
		// bloom filters + token + values => we can't include both sets of nodes, even if the node requests it
		if (heavyWeight) {
			if (v6)
				kns4 = null;
			else
				kns6 = null;
		}
		
		GetPeersResponse resp = new GetPeersResponse(r.getMTID(),
			kns4 != null ? kns4.pack() : null,
			kns6 != null ? kns6.pack() : null,
			token != null ? token.arr : null);
		
		resp.setScrapePeers(peerFilter);
		resp.setScrapeSeeds(seedFilter);

		
		resp.setPeerItems(dbl);
		resp.setDestination(r.getOrigin());
		r.getServer().sendMessage(resp);
	}

	public void announce(AnnounceRequest r) {
		
		if (!isRunning()) {
			return;
		}

		// ignore requests we get from ourself
		if (node.isLocalId(r.getID())) {
			return;
		}

		node.recieved(r);
		// first check if the token is OK
		ByteWrapper token = new ByteWrapper(r.getToken());
		if (!db.checkToken(token, r.getOrigin().getAddress(), r.getOrigin().getPort(), r.getInfoHash())) {
			logDebug("DHT Received Announce Request with invalid token.");
			sendError(r, ErrorCode.ProtocolError.code, "Invalid Token; tokens expire after "+DHTConstants.TOKEN_TIMEOUT+"ms; only valid for the IP/port to which it was issued; only valid for the infohash for which it was issued");
			return;
		}

		logDebug("DHT Received Announce Request, adding peer to db: "
				+ r.getOrigin().getAddress());

		// everything OK, so store the value
		PeerAddressDBItem item = PeerAddressDBItem.createFromAddress(r.getOrigin().getAddress(), r.getPort(), r.isSeed());
		if (!AddressUtils.isBogon(item))
			db.store(r.getInfoHash(), item);

		// send a proper response to indicate everything is OK
		AnnounceResponse rsp = new AnnounceResponse(r.getMTID());
		rsp.setDestination(r.getOrigin());
		r.getServer().sendMessage(rsp);
	}

	public void error (ErrorMessage r) {
		DHT.logError("Error [" + r.getCode() + "] from: " + r.getOrigin()
				+ " Message: \"" + r.getMessage() + "\" version:"+r.getVersion());
	}

	public void timeout (RPCCall r) {
		if (isRunning()) {
			node.onTimeout(r);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#addDHTNode(java.lang.String, int)
	 */
	public void addDHTNode (String host, int hport) {
		if (!isRunning()) {
			return;
		}
		InetSocketAddress addr = new InetSocketAddress(host, hport);

		if (!addr.isUnresolved() && !AddressUtils.isBogon(addr)) {
			if (!type.PREFERRED_ADDRESS_TYPE.isInstance(addr.getAddress()) || node.getNumEntriesInRoutingTable() > DHTConstants.BOOTSTRAP_IF_LESS_THAN_X_PEERS)
				return;
			serverManager.getRandomActiveServer(true).ping(addr);
		}

	}

	/**
	 * returns a non-enqueued task for further configuration. or zero if the request cannot be serviced.
	 * use the task-manager to actually start the task.
	 */
	public PeerLookupTask createPeerLookup (byte[] info_hash) {
		if (!isRunning()) {
			return null;
		}
		Key id = new Key(info_hash);
		
		RPCServer srv = serverManager.getRandomActiveServer(false);
		if (srv == null)
			return null;

		PeerLookupTask lookupTask = new PeerLookupTask(srv, node, id);

		return lookupTask;
	}
	
	public AnnounceTask announce(PeerLookupTask lookup, boolean isSeed, int btPort) {
		if (!isRunning()) {
			return null;
		}
		
		// reuse the same server to make sure our tokens are still valid
		AnnounceTask announce = new AnnounceTask(lookup.getRPC(), node, lookup.getInfoHash(), btPort);
		announce.setSeed(isSeed);
		for (KBucketEntryAndToken kbe : lookup.getAnnounceCanidates()) {
			announce.addToTodo(kbe);
		}

		tman.addTask(announce);

		return announce;
	}
	
	
	public DHTConfiguration getConfig() {
		return config;
	}
	
	public AnnounceNodeCache getCache() {
		return cache;
	}
	
	public RPCServerManager getServerManager() {
		return serverManager;
	}
	
	public NIOConnectionManager getConnectionManager() {
		return connectionManager;
	}
	
	public PopulationEstimator getEstimator() {
		return estimator;
	}

	public DHTtype getType() {
		return type;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#getStats()
	 */
	public DHTStats getStats () {
		return stats;
	}

	/**
	 * @return the status
	 */
	public DHTStatus getStatus () {
		return status;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#isRunning()
	 */
	public boolean isRunning () {
		return running;
	}

	private int getPort() {
		int port = config.getListeningPort();
		if (port < 1 || port > 65535)
			port = 49001;
		return port;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#start(java.lang.String, int)
	 */
	public void start(DHTConfiguration config)
			throws SocketException {
		
		if (running) {
			return;
		}

		this.config = config;
		useRouterBootstrapping = !config.noRouterBootstrap();

		setStatus(DHTStatus.Initializing);
		stats.resetStartedTimestamp();

		tableFile = config.getNodeCachePath();
		Node.initDataStore(config);

		logInfo("Starting DHT on port " + getPort());
		resolveBootstrapAddresses();
		
		serverStats = new RPCStats();

		cache = new AnnounceNodeCache();
		stats.setRpcStats(serverStats);
		connectionManager = new NIOConnectionManager("mlDHT "+type.shortName+" NIO Selector");
		serverManager = new RPCServerManager(this);
		node = new Node(this);
		db = new Database();
		stats.setDbStats(db.getStats());
		tman = new TaskManager(this);
		running = true;
		
		// these checks are fairly expensive on large servers (network interface enumeration)
		// schedule them separately
		//scheduledActions.add(scheduler.scheduleWithFixedDelay(serverManager::doBindChecks, 10, 10, TimeUnit.SECONDS));
		scheduledActions.add(scheduler.scheduleWithFixedDelay(new Runnable() {
			@Override
			public void run() {
				serverManager.doBindChecks();
			}
		}
		, 10, 10, TimeUnit.SECONDS));
		
		/*scheduledActions.add(scheduler.scheduleAtFixedRate(() -> {
			// maintenance that should run all the time, before the first queries
			tman.dequeue();

			if (running)
				onStatsUpdate();
		}, 5000, DHTConstants.DHT_UPDATE_INTERVAL, TimeUnit.MILLISECONDS));*/
		scheduledActions.add(scheduler.scheduleAtFixedRate(new Runnable() {
			@Override
			public void run() {
				// maintenance that should run all the time, before the first queries
				tman.dequeue();

				if (running)
					onStatsUpdate();
			}
		}, 5000, DHTConstants.DHT_UPDATE_INTERVAL, TimeUnit.MILLISECONDS));
		
		// initialize as many RPC servers as we need
		serverManager.refresh(System.currentTimeMillis());
		
		bootstrapping = true;
		node.loadTable();
		
		started();
		
//		// does 10k random lookups and prints them to a file for analysis
//		scheduler.schedule(new Runnable() {
//			//PrintWriter		pw;
//			TaskListener	li	= new TaskListener() {
//									public synchronized void finished(Task t) {
//										NodeLookup nl = ((NodeLookup) t);
//										if (nl.closestSet.size() < DHTConstants.MAX_ENTRIES_PER_BUCKET)
//											return;
//										/*
//										StringBuilder b = new StringBuilder();
//										b.append(nl.targetKey.toString(false));
//										b.append(",");
//										for (Key i : nl.closestSet)
//											b.append(i.toString(false).substring(0, 12) + ",");
//										b.deleteCharAt(b.length() - 1);
//										pw.println(b);
//										pw.flush();
//										*/
//									}
//								};
//
//			public void run() {
//				if (type == DHTtype.IPV6_DHT)
//					return;
//				/*
//				try
//				{
//					pw = new PrintWriter("H:\\mldht.log");
//				} catch (FileNotFoundException e)
//				{
//					e.printStackTrace();
//				}*/
//				for (int i = 0; i < 10000; i++)
//				{
//					NodeLookup l = new NodeLookup(Key.createRandomKey(), srv, node, false);
//					if (canStartTask())
//						l.start();
//					tman.addTask(l);
//					l.addListener(li);
//					if (i == (10000 - 1))
//						l.addListener(new TaskListener() {
//							public void finished(Task t) {
//								System.out.println("10k lookups done");
//							}
//						});
//				}
//			}
//		}, 1, TimeUnit.MINUTES);
		

	}
	
	
	


	public void started () {
		
		// refresh everything during startup
		List<RoutingTableEntry> tableEntries = node.getBuckets();
		
		for (RoutingTableEntry bucket : tableEntries) {
			RPCServer srv = serverManager.getRandomServer();
			if (srv == null)
				break;
			Task t = new PingRefreshTask(srv, node, bucket.getBucket(), true);
			t.setInfo("Startup ping for " + bucket.prefix);
			if (t.getTodoCount() > 0)
				tman.addTask(t);
		}
		
		bootstrapping = false;
		bootstrap();
		
		/*
		if (type == DHTtype.IPV6_DHT) {
			Task t = new KeyspaceCrawler(srv, node);
			tman.addTask(t);
		}*/
		
		scheduledActions.add(scheduler.scheduleWithFixedDelay(
		/*() -> {
			try {
				update();
			} catch (RuntimeException e) {
				log(e, LogLevel.Fatal);
			}
		}*/
		new Runnable() {
			@Override
			public void run() {
				try {
					update();
				} catch (RuntimeException e) {
					log(e, LogLevel.Fatal);
				}
			}
		},
		5000, DHTConstants.DHT_UPDATE_INTERVAL, TimeUnit.MILLISECONDS));
		
		scheduledActions.add(scheduler.scheduleWithFixedDelay(
		/*() -> {
			try {
				long now = System.currentTimeMillis();
				db.expire(now);
				cache.cleanup(now);
			} catch (Exception e) {
				log(e, LogLevel.Fatal);
			}
		}*/
		new Runnable() {
			@Override
			public void run() {
				try {
					long now = System.currentTimeMillis();
					db.expire(now);
					cache.cleanup(now);
				} catch (Exception e) {
					log(e, LogLevel.Fatal);
				}
			}
		}, 1000, DHTConstants.CHECK_FOR_EXPIRED_ENTRIES, TimeUnit.MILLISECONDS));
		
		// single ping to a random node per server to check socket liveness
		scheduledActions.add(scheduler.scheduleWithFixedDelay(
		/*() -> {
			for (RPCServer srv : serverManager.getAllServers()) {
				if (srv.getNumActiveRPCCalls() > 0)
					continue;
				node.getRandomEntry().ifPresent((entry) -> {
					PingRequest req = new PingRequest();
					req.setDestination(entry.getAddress());
					RPCCall call = new RPCCall(srv, req);
					call.setExpectedID(entry.getID());
					call.start();
				});
			};
		}*/
		new Runnable() {
			@Override
			public void run() {
				for (RPCServer srv : serverManager.getAllServers()) {
					
					if (srv.getNumActiveRPCCalls() > 0)
						continue;
					
					/*node.getRandomEntry().ifPresent((entry) -> {
						PingRequest req = new PingRequest();
						req.setDestination(entry.getAddress());
						RPCCall call = new RPCCall(srv, req);
						call.setExpectedID(entry.getID());
						call.start();
					});*/
					
					node.getRandomEntry();
					
				}
			}
		}, 1, 10, TimeUnit.SECONDS));
		
		
		// deep lookup to make ourselves known to random parts of the keyspace
		scheduledActions.add(scheduler.scheduleWithFixedDelay(() -> {
			try {
				for (RPCServer srv : serverManager.getAllServers())
					findNode(Key.createRandomKey(), false, false, srv).setInfo("Random Refresh Lookup");
			} catch (RuntimeException e1) {
				log(e1, LogLevel.Fatal);
			}
			
			try {
				if (!node.isInSurvivalMode())
					node.saveTable(tableFile);
			} catch (IOException e2) {
				e2.printStackTrace();
			}
		}, DHTConstants.RANDOM_LOOKUP_INTERVAL, DHTConstants.RANDOM_LOOKUP_INTERVAL, TimeUnit.MILLISECONDS));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#stop()
	 */
	public void stop () {
		if (!running) {
			return;
		}

		//scheduler.shutdown();
		logInfo("Stopping DHT");
		for (Task t : tman.getActiveTasks()) {
			t.kill();
		}
		
		for (ScheduledFuture<?> future : scheduledActions)
			future.cancel(false);
		scheduler.getQueue().removeAll(scheduledActions);
		scheduledActions.clear();

		serverManager.destroy();
		try {
			node.saveTable(tableFile);
		} catch (IOException e) {
			e.printStackTrace();
		}
		running = false;
		stopped();
		tman = null;
		db = null;
		node = null;
		cache = null;
		serverManager = null;
		setStatus(DHTStatus.Stopped);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#getNode()
	 */
	public Node getNode () {
		return node;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#getTaskManager()
	 */
	public TaskManager getTaskManager () {
		return tman;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#stopped()
	 */
	public void stopped () {
		// TODO Auto-generated method stub

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#update()
	 */
	public void update () {
		
		long now = System.currentTimeMillis();
		
		serverManager.refresh(now);
		
		if (!isRunning()) {
			return;
		}

		node.doBucketChecks(now);

		if (!bootstrapping) {
			if (node.getNumEntriesInRoutingTable() < DHTConstants.BOOTSTRAP_IF_LESS_THAN_X_PEERS || now - lastBootstrap > DHTConstants.SELF_LOOKUP_INTERVAL) {
				//regualary search for our id to update routing table
				bootstrap();
			} else {
				setStatus(DHTStatus.Running);
			}
		}

		
	}
	
	
	private void resolveBootstrapAddresses() {
		List<InetSocketAddress> nodeAddresses =  new ArrayList<InetSocketAddress>();
		for (int i = 0;i<DHTConstants.BOOTSTRAP_NODES.length;i++) {
			try {
				String hostname = DHTConstants.BOOTSTRAP_NODES[i];
				int port = DHTConstants.BOOTSTRAP_PORTS[i];
			

				 for (InetAddress addr : InetAddress.getAllByName(hostname)) {
					 nodeAddresses.add(new InetSocketAddress(addr, port));
				 }
			} catch (Exception e) {
				// do nothing
			}
		}
		
		if (nodeAddresses.size() > 0)
			DHTConstants.BOOTSTRAP_NODE_ADDRESSES = nodeAddresses;
	}

	/**
	 * Initiates a Bootstrap.
	 * 
	 * This function bootstraps with router.bittorrent.com if there are less
	 * than 10 Peers in the routing table. If there are more then a lookup on
	 * our own ID is initiated. If the either Task is finished than it will try
	 * to fill the Buckets.
	 */
	public synchronized void bootstrap () {
		
		if (!isRunning() || bootstrapping || System.currentTimeMillis() - lastBootstrap < DHTConstants.BOOTSTRAP_MIN_INTERVAL) {
			return;
		}
		
		//Log.d(TAG, "useRouterBootstrapping = " + useRouterBootstrapping);
		//Log.d(TAG, "node.getNumEntriesInRoutingTable() = " + node.getNumEntriesInRoutingTable());
		
		if (useRouterBootstrapping || node.getNumEntriesInRoutingTable() > 1) {
			
			final AtomicInteger finishCount = new AtomicInteger();
			bootstrapping = true;
			
			TaskListener bootstrapListener = t -> {
				int count = finishCount.decrementAndGet();
				if (count == 0)
					bootstrapping = false;
				// fill the remaining buckets once all bootstrap operations finished
				if (count == 0 && running && node.getNumEntriesInRoutingTable() > DHTConstants.USE_BT_ROUTER_IF_LESS_THAN_X_PEERS) {
					node.fillBuckets(DHT.this);
				}
			};

			logInfo("Bootstrapping...");
			lastBootstrap = System.currentTimeMillis();

			for (RPCServer srv : serverManager.getAllServers()) {
				finishCount.incrementAndGet();
				NodeLookup nl = findNode(srv.getDerivedID(), true, true, srv);
				if (nl == null) {
					bootstrapping = false;
					break;
				} else if (node.getNumEntriesInRoutingTable() < DHTConstants.USE_BT_ROUTER_IF_LESS_THAN_X_PEERS) {
					if (useRouterBootstrapping) {
						resolveBootstrapAddresses();
						List<InetSocketAddress> addrs = new ArrayList<InetSocketAddress>(DHTConstants.BOOTSTRAP_NODE_ADDRESSES);
						Collections.shuffle(addrs);
						
						for (InetSocketAddress addr : addrs) {
							if (!type.PREFERRED_ADDRESS_TYPE.isInstance(addr.getAddress()))
								continue;
							nl.addDHTNode(addr.getAddress(),addr.getPort());
							break;
						}
					}
					nl.addListener(bootstrapListener);
					nl.setInfo("Bootstrap: Find Peers.");

					tman.dequeue();

				} else {
					nl.setInfo("Bootstrap: search for ourself.");
					nl.addListener(bootstrapListener);
					tman.dequeue();
				}
				
			}
		}
	}

	private NodeLookup findNode (Key id, boolean isBootstrap,
			boolean isPriority, RPCServer server) {
		if (!running || server == null) {
			return null;
		}

		NodeLookup at = new NodeLookup(id, server, node, isBootstrap);
		tman.addTask(at, isPriority);
		return at;
	}

	/**
	 * Do a NodeLookup.
	 * 
	 * @param id The id of the key to search
	 */
	public NodeLookup findNode (Key id) {
		return findNode(id, false, false,serverManager.getRandomActiveServer(true));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#fillBucket(lbms.plugins.mldht.kad.KBucket)
	 */
	public NodeLookup fillBucket (Key id, KBucket bucket) {
		bucket.updateRefreshTimer();
		return findNode(id, false, true, serverManager.getRandomActiveServer(true));
	}

	public void sendError (MessageBase origMsg, int code, String msg) {
		sendError(origMsg.getOrigin(), origMsg.getMTID(), code, msg, origMsg.getServer());
	}

	public void sendError (InetSocketAddress target, byte[] mtid, int code,
			String msg, RPCServer srv) {
		ErrorMessage errMsg = new ErrorMessage(mtid, code, msg);
		errMsg.setDestination(target);
		srv.sendMessage(errMsg);
	}

	public Key getOurID () {
		if (running) {
			return node.getRootID();
		}
		return null;
	}

	private void onStatsUpdate () {
		stats.setNumTasks(tman.getNumTasks() + tman.getNumQueuedTasks());
		stats.setNumPeers(node.getNumEntriesInRoutingTable());
		long numSent = 0;long numReceived = 0;int activeCalls = 0;
		for (RPCServer s : serverManager.getAllServers()) {
			numSent += s.getNumSent();
			numReceived += s.getNumReceived();
			activeCalls += s.getNumActiveRPCCalls();
		}
		stats.setNumSentPackets(numSent);
		stats.setNumReceivedPackets(numReceived);
		stats.setNumRpcCalls(activeCalls);

		for (int i = 0; i < statsListeners.size(); i++) {
			statsListeners.get(i).statsUpdated(stats);
		}
	}

	private void setStatus (DHTStatus status) {
		if (!this.status.equals(status)) {
			DHTStatus old = this.status;
			this.status = status;
			if (!statusListeners.isEmpty()) {
				for (int i = 0; i < statusListeners.size(); i++) {
					statusListeners.get(i).statusChanged(status, old);
				}
			}
		}
	}

	public void addStatsListener (DHTStatsListener listener) {
		statsListeners.add(listener);
	}

	public void removeStatsListener (DHTStatsListener listener) {
		statsListeners.remove(listener);
	}

	public void addIndexingListener(DHTIndexingListener listener) {
		indexingListeners.add(listener);
	}

	public void addStatusListener (DHTStatusListener listener) {
		statusListeners.add(listener);
	}

	public void removeStatusListener (DHTStatusListener listener) {
		statusListeners.remove(listener);
	}
	
	public void printDiagnostics(PrintWriter w) {
		//StringBuilder b = new StringBuilder();

		for (ScheduledFuture<?> f : scheduledActions)
			if (f.isDone()) { // check for exceptions
				try {
					f.get();
				} catch (ExecutionException | InterruptedException e) {
					e.printStackTrace(w);
				}

			}
				
		
		w.println("==========================");
		w.println("DHT Diagnostics. Type "+type);
		w.println("# of active servers / all servers: "+ serverManager.getActiveServerCount()+ '/'+ serverManager.getServerCount());
		
		if (!isRunning())
			return;
		
		w.append("-----------------------\n");
		w.append("Stats\n");
		w.append("Reachable node estimate: "+ estimator.getEstimate()+'\n');
		w.append(stats.toString());
		w.append("-----------------------\n");
		w.append("Routing table\n");
		w.append(node.toString());
		w.append("-----------------------\n");
		w.append("RPC Servers\n");
		for (RPCServer srv : serverManager.getAllServers())
			w.append(srv.toString());
		w.append("-----------------------\n");
		w.append("Lookup Cache\n");
		cache.printDiagnostics(w);
		w.append("-----------------------\n");
		w.append("Tasks\n");
		w.append(tman.toString());
		w.append("\n\n\n");
	}

	/**
	 * @return the logger
	 */
	//	public static DHTLogger getLogger () {
	//		return logger;
	//	}
	/**
	 * @param logger the logger to set
	 */
	public static void setLogger (DHTLogger logger) {
		DHT.logger = logger;
	}

	/**
	 * @return the logLevel
	 */
	public static LogLevel getLogLevel () {
		return logLevel;
	}

	/**
	 * @param logLevel the logLevel to set
	 */
	public static void setLogLevel (LogLevel logLevel) {
		DHT.logLevel = logLevel;
		logger.log("Change LogLevel to: " + logLevel, LogLevel.Info);
	}

	/**
	 * @return the scheduler
	 */
	public static ScheduledExecutorService getScheduler () {
		return scheduler;
	}

	public static void log (String message, LogLevel level) {
		if (level.compareTo(logLevel) < 1) { // <=
			logger.log(message, level);
		}
	}

	public static void log (Throwable e, LogLevel level) {
		if (level.compareTo(logLevel) < 1) { // <=
			logger.log(e, level);
		}
	}

	public static void logFatal (String message) {
		log(message, LogLevel.Fatal);
	}

	public static void logError (String message) {
		log(message, LogLevel.Error);
	}

	public static void logInfo (String message) {
		log(message, LogLevel.Info);
	}

	public static void logDebug (String message) {
		log(message, LogLevel.Debug);
	}

	public static void logVerbose (String message) {
		log(message, LogLevel.Verbose);
	}

	public static boolean isLogLevelEnabled (LogLevel level) {
		return level.compareTo(logLevel) < 1;
	}

	public static enum LogLevel {
		Fatal, Error, Info, Debug, Verbose
	}
}
