package lbms.plugins.mldht.kad;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import lbms.plugins.mldht.kad.utils.AddressUtils;
import lbms.plugins.mldht.kad.utils.ThreadLocalUtils;

public class RPCServerManager {
	
	boolean destroyed;
	
	public RPCServerManager(DHT dht) {
		this.dht = dht;
		updateBindAddrs();
	}
	
	DHT dht;
	private ConcurrentHashMap<InetAddress,RPCServer> interfacesInUse = new ConcurrentHashMap<InetAddress, RPCServer>();
	private List<InetAddress> validBindAddresses = Collections.emptyList();
	private volatile RPCServer[] activeServers = new RPCServer[0];
	
	public void refresh(long now) {
		if (destroyed)
			return;
		
		startNewServers();
		
		List<RPCServer> reachableServers = new ArrayList<RPCServer>(interfacesInUse.values().size());
		for (Iterator<RPCServer> it = interfacesInUse.values().iterator();it.hasNext();) {
			RPCServer srv = it.next();
			srv.checkReachability(now);
			if (srv.isReachable())
				reachableServers.add(srv);
		}
		
		activeServers = reachableServers.toArray(new RPCServer[reachableServers.size()]);
	}
	
	private void updateBindAddrs() {
		try {
			Class<? extends InetAddress> type = dht.getType().PREFERRED_ADDRESS_TYPE;
			
			List<InetAddress> newBindAddrs = Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
					.flatMap(iface -> iface.getInterfaceAddresses().stream())
					.map(ifa -> ifa.getAddress())
					.filter(addr -> type.isInstance(addr))
					.distinct()
					.collect(Collectors.toCollection(() -> new ArrayList<>()));
			
			newBindAddrs.add(AddressUtils.getAnyLocalAddress(type));
			
			validBindAddresses = newBindAddrs;
			
		} catch (SocketException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public void doBindChecks() {
		updateBindAddrs();
		getAllServers().forEach(srv -> {
			if (!validBindAddresses.contains(srv.getBindAddress())) {
				srv.stop();
			}
		});
		
	}
	
	private void startNewServers() {
		
		boolean multihome = dht.config.allowMultiHoming();
		Class<? extends InetAddress> addressType = dht.getType().PREFERRED_ADDRESS_TYPE;
		
		
		if (multihome) {
			// we only consider global unicast addresses in multihoming mode
			// this is mostly meant for server configurations
			List<InetAddress> addrs = new ArrayList<>(AddressUtils.getAvailableGloballyRoutableAddrs(addressType));
			addrs.removeAll(interfacesInUse.keySet());
			// create new servers for all IPs we aren't currently haven't bound
			addrs.forEach(addr -> newServer(addr));
			return;
		}
		
		// single home
		RPCServer current = interfacesInUse.values().stream().findAny().orElse(null);
		
		// check if we have bound to an anylocaladdress because we didn't know any better and consensus converged on a local address
		// that's mostly going to happen on v6 if we can't find a default route for v6
		if (current != null && current.getBindAddress().isAnyLocalAddress() && current.getConsensusExternalAddress() != null && AddressUtils.isValidBindAddress(current.getConsensusExternalAddress().getAddress())) {
			InetAddress rebindAddress = current.getConsensusExternalAddress().getAddress();
			current.stop();
			newServer(rebindAddress);
			return;
		}
		
		// single homed & already have a server -> no need for another one
		if (current != null)
			return;
		
		// this is our default strategy. try to determine the default route
		InetAddress defaultBind = AddressUtils.getDefaultRoute(addressType);
		
		if (defaultBind != null) {
			newServer(defaultBind);
			return;
		}
		
		// last resort for v6, try a random global unicast address, otherwise anylocal
		if (addressType.isAssignableFrom(Inet6Address.class)) {
			InetAddress addr = AddressUtils.getAvailableGloballyRoutableAddrs(addressType).stream().findAny().orElse(AddressUtils.getAnyLocalAddress(addressType));
			newServer(addr);
			return;
		}
		
		// last resort for v4, bind to anylocal address since we don't know  how to pick among multiple NATed routes
		newServer(AddressUtils.getAnyLocalAddress(addressType));
	}
	
	private void newServer(InetAddress addr) {
		RPCServer srv = new RPCServer(this,addr,dht.config.getListeningPort(), dht.serverStats);
		srv.start();
		interfacesInUse.put(addr, srv);
	}
	
	void serverRemoved(RPCServer srv) {
		interfacesInUse.remove(srv.getBindAddress(),srv);
		refresh(System.currentTimeMillis());
	}
	
	public void destroy() {
		destroyed = true;
		for (RPCServer srv : new ArrayList<RPCServer>(interfacesInUse.values()))
			srv.stop();
	}
	
	public int getServerCount() {
		return interfacesInUse.size();
	}
	
	public int getActiveServerCount() {
		return activeServers.length;
	}
	
	public RPCServer getRandomActiveServer(boolean fallback) {
		RPCServer[] srvs = activeServers;
		if (srvs.length == 0)
			return fallback ? getRandomServer() : null;
		return srvs[ThreadLocalUtils.getThreadLocalRandom().nextInt(srvs.length)];
	}
	
	/**
	 * this method is relatively expensive... don't call it frequently
	 */
	public RPCServer getRandomServer() {
		List<RPCServer> servers = getAllServers();
		if (servers.isEmpty())
			return null;
		return servers.get(ThreadLocalUtils.getThreadLocalRandom().nextInt(servers.size()));
	}
	
	public List<RPCServer> getAllServers() {
		return new ArrayList<RPCServer>(interfacesInUse.values());
	}
	
}
