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

import static the8472.bencode.Utils.prettyPrint;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import lbms.plugins.mldht.kad.DHT.LogLevel;
import lbms.plugins.mldht.kad.messages.ErrorMessage;
import lbms.plugins.mldht.kad.messages.ErrorMessage.ErrorCode;
import lbms.plugins.mldht.kad.messages.FindNodeResponse;
import lbms.plugins.mldht.kad.messages.MessageBase;
import lbms.plugins.mldht.kad.messages.MessageBase.Type;
import lbms.plugins.mldht.kad.messages.AnnounceRequest;
import lbms.plugins.mldht.kad.messages.FindNodeRequest;
import lbms.plugins.mldht.kad.messages.GetPeersRequest;
import lbms.plugins.mldht.kad.messages.MessageDecoder;
import lbms.plugins.mldht.kad.messages.MessageException;
import lbms.plugins.mldht.kad.messages.PingRequest;
import lbms.plugins.mldht.kad.messages.PingResponse;
import lbms.plugins.mldht.kad.utils.AddressUtils;
import lbms.plugins.mldht.kad.utils.ByteWrapper;
import lbms.plugins.mldht.kad.utils.ResponseTimeoutFilter;
import lbms.plugins.mldht.kad.utils.ThreadLocalUtils;
import lbms.plugins.mldht.utils.NIOConnectionManager;
import lbms.plugins.mldht.utils.Selectable;
import the8472.bencode.Utils;

/**
 * @author The_8472, Damokles
 *
 */
public class RPCServer {
	
	private InetAddress								addr;
	private DHT										dht;
	private RPCServerManager						manager;
	private ConcurrentMap<ByteWrapper, RPCCall>		calls;
	private Queue<RPCCall>							call_queue;
	private Queue<EnqueuedSend>						pipeline;
	private volatile int							numReceived;
	private volatile int							numSent;
	private int										port;
	private Instant									startTime;
	private RPCStats								stats;
	private ResponseTimeoutFilter					timeoutFilter;
	private Key										derivedId;
	private InetSocketAddress						consensusExternalAddress;
	private SpamThrottle 							throttle = new SpamThrottle();
	
	private LinkedHashMap<InetAddress, InetSocketAddress> originPairs  = new LinkedHashMap<InetAddress, InetSocketAddress>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<InetAddress,InetSocketAddress> eldest) {
			if (this.size() > 64)
				return true;
			return false;
		};
	};
	
	
	private volatile boolean isReachable = false;
	private int		numReceivesAtLastCheck = 0;
	private long	timeOfLastReceiveCountChange = 0;
	

	private SocketHandler sel;

	public RPCServer (RPCServerManager manager, InetAddress addr, int port, RPCStats stats) {
		this.port = port;
		this.dht = manager.dht;
		timeoutFilter = new ResponseTimeoutFilter();
		pipeline = new ConcurrentLinkedQueue<EnqueuedSend>();
		calls = new ConcurrentHashMap<ByteWrapper, RPCCall>(80,0.75f,3);
		call_queue = new ConcurrentLinkedQueue<RPCCall>();
		this.stats = stats;
		this.addr = addr;
		this.manager = manager;
		// reserve an ID
		derivedId = dht.getNode().registerServer(this);
	}
	
	public DHT getDHT() {
		return dht;
	}
	
	public int getPort() {
		return port;
	}
	
	public InetAddress getBindAddress() {
		return addr;
	}
	
	/**
	 * @return external addess, if known (only ipv6 for now)
	 */
	public InetAddress getPublicAddress() {
		if (sel == null)
			return null;
		InetAddress addr = ((DatagramChannel)sel.getChannel()).socket().getLocalAddress();
		if (dht.getType().PREFERRED_ADDRESS_TYPE.isInstance(addr) && AddressUtils.isGlobalUnicast(addr))
			return addr;
		return null;
	}

	
	public Key getDerivedID() {
		return derivedId;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.RPCServerBase#start()
	 */
	public void start() {
		DHT.logInfo("Starting RPC Server");
		sel = new SocketHandler();
		startTime = Instant.now();
	}
	
	public void stop() {
		try {
			sel.close();
		} catch (IOException e) {
			DHT.log(e, LogLevel.Error);
		}
		dht.getNode().removeServer(this);
		manager.serverRemoved(this);
		pipeline.clear();
	}


	/* (non-Javadoc)
	 * @see lbms.plugins.mldht.kad.RPCServerBase#doCall(lbms.plugins.mldht.kad.messages.MessageBase)
	 */
	void doCall (RPCCall c) {
		
		while (true) {
			
			if (calls.size() >= DHTConstants.MAX_ACTIVE_CALLS) {
				DHT.logInfo("Queueing RPC call, no slots available at the moment");
				call_queue.add(c);
				break;
			}
			byte[] mtid = new byte[6];
			ThreadLocalUtils.getThreadLocalRandom().nextBytes(mtid);
			if (calls.putIfAbsent(new ByteWrapper(mtid),c) == null) {
				dispatchCall(c, mtid);
				break;
			}
		}
	}
	
	private final RPCCallListener rpcListener = new RPCCallListener() {
		
		public void onTimeout(RPCCall c) {
			ByteWrapper w = new ByteWrapper(c.getRequest().getMTID());
			stats.addTimeoutMessageToCount(c.getRequest());
			calls.remove(w);
			dht.timeout(c);
			doQueuedCalls();
		}
		
		public void onStall(RPCCall c) {}
		public void onResponse(RPCCall c, MessageBase rsp) {}
	};
	
	/* (non-Javadoc)
	 * @see lbms.plugins.mldht.kad.RPCServerBase#ping(lbms.plugins.mldht.kad.Key, java.net.InetSocketAddress)
	 */
	public void ping (InetSocketAddress addr) {
		PingRequest pr = new PingRequest();
		pr.setID(derivedId);
		pr.setDestination(addr);
		new RPCCall(this, pr).start();
	}

	/* (non-Javadoc)
	 * @see lbms.plugins.mldht.kad.RPCServerBase#findCall(byte)
	 */
	public RPCCall findCall (byte[] mtid) {
		return calls.get(new ByteWrapper(mtid));
	}

	/// Get the number of active calls
	/* (non-Javadoc)
	 * @see lbms.plugins.mldht.kad.RPCServerBase#getNumActiveRPCCalls()
	 */
	public int getNumActiveRPCCalls () {
		return calls.size();
	}

	/**
	 * @return the numReceived
	 */
	public int getNumReceived () {
		return numReceived;
	}

	/**
	 * @return the numSent
	 */
	public int getNumSent () {
		return numSent;
	}

	/* (non-Javadoc)
	 * @see lbms.plugins.mldht.kad.RPCServerBase#getStats()
	 */
	public RPCStats getStats () {
		return stats;
	}
	
	public void checkReachability(long now) {
		// don't do pings too often if we're not receiving anything (connection might be dead)
		if (numReceived != numReceivesAtLastCheck) {
			isReachable = true;
			timeOfLastReceiveCountChange = now;
			numReceivesAtLastCheck = numReceived;
		} else if (now - timeOfLastReceiveCountChange > DHTConstants.REACHABILITY_TIMEOUT) {
			isReachable = false;
			timeoutFilter.reset();
		}
	}
	
	public boolean isReachable() {
		return isReachable;
	}
	
	private void handlePacket(ByteBuffer p, SocketAddress soa) {
		InetSocketAddress source = (InetSocketAddress) soa;
		
		// ignore port 0, can't respond to them anyway and responses to requests from port 0 will be useless too
		if (source.getPort() == 0)
			return;

		
		Map<String, Object> bedata = null;
		MessageBase msg = null;
		
		try {
			bedata = ThreadLocalUtils.getDecoder().decode(p);
			
			try {
				if (DHT.isLogLevelEnabled(LogLevel.Verbose)) {
					DHT.logVerbose("received: " + Utils.prettyPrint(bedata) + " from: " + source);
				}
			} catch (Exception e) {
				DHT.log(e, LogLevel.Error);
			}
		} catch(Exception e) {
			p.rewind();
			DHT.logError("failed to decode message  " + Utils.stripToAscii(p) + " (length:"+p.remaining()+") from: " + source);
			DHT.log(e, LogLevel.Debug);
			MessageBase err = new ErrorMessage(new byte[] {0,0,0,0}, ErrorCode.ProtocolError.code,"invalid bencoding: "+e.getMessage());
			err.setDestination(source);
			sendMessage(err);
			return;
		}
		
		try {
			msg = MessageDecoder.parseMessage(bedata, this);
		} catch(MessageException e) {
			byte[] mtid = {0,0,0,0};
			if (bedata.containsKey(MessageBase.TRANSACTION_KEY) && bedata.get(MessageBase.TRANSACTION_KEY) instanceof byte[])
				mtid = (byte[]) bedata.get("t");
			DHT.log(e.getMessage(), LogLevel.Debug);
			MessageBase err = new ErrorMessage(mtid, e.errorCode.code,e.getMessage());
			err.setDestination(source);
			sendMessage(err);
			return;
		} catch(IOException e) {
			DHT.log(e, LogLevel.Error);
		}
		
		if (msg == null)
			return;
		
		if (DHT.isLogLevelEnabled(LogLevel.Debug))
			DHT.logDebug("RPC received message from "+source.getAddress().getHostAddress() + ":" + source.getPort() +" | "+msg.toString());
		stats.addReceivedMessageToCount(msg);
		msg.setOrigin(source);
		msg.setServer(this);
		
		// just respond to incoming requests, no need to match them to pending requests
		if (msg.getType() == Type.REQ_MSG) {
			handleMessage(msg);
			return;
		}
		
			
		
		// check if this is a response to an outstanding request
		RPCCall c = calls.get(new ByteWrapper(msg.getMTID()));
		
		// message matches transaction ID and origin == destination
		if (c != null) {
										
			if (c.getRequest().getDestination().equals(msg.getOrigin())) {
				// remove call first in case of exception
				calls.remove(new ByteWrapper(msg.getMTID()),c);
				msg.setAssociatedCall(c);
				c.response(msg);

				doQueuedCalls();
				// apply after checking for a proper response
				handleMessage(msg);
				
				return;
			}
			
			// 1. the message is not a request
			// 2. transaction ID matched
			// 3. request destination did not match response source!!
			// 4. we're using random 48 bit MTIDs
			// this happening by chance is exceedingly unlikely
			
			// either a bug or an attack -> drop message
			
			DHT.logError("mtid matched, IP did not, ignoring message, request: " + c.getRequest().getDestination() + " -> response: " + msg.getOrigin());
			
			return;
		}
		
		// a) it's a response b) didn't find a call c) uptime is high enough that it's not a stray from a restart
		// -> did not expect this response
		if (msg.getType() == Type.RSP_MSG && Duration.between(startTime, Instant.now()).getSeconds() > 2*60) {
			byte[] mtid = msg.getMTID();
			DHT.logDebug("Cannot find RPC call for response: "+ Utils.prettyPrint(mtid));
			ErrorMessage err = new ErrorMessage(mtid, ErrorCode.ServerError.code, "received a response message whose transaction ID did not match a pending request or transaction expired");
			err.setDestination(msg.getOrigin());
			sendMessage(err);
			return;
		}

		if (msg.getType() == Type.ERR_MSG) {
			handleMessage(msg);
			return;
		}
		
		DHT.logError("not sure how to handle message " + msg);
	}
	
	private void handleMessage(MessageBase msg) {
		if (msg.getType() == Type.RSP_MSG && msg.getPublicIP() != null)
			updatePublicIPConsensus(msg.getOrigin().getAddress(), msg.getPublicIP());
		dht.incomingMessage(msg);
		msg.apply(dht);
	}
	
	private void updatePublicIPConsensus(InetAddress source, InetSocketAddress addr) {
		if (!AddressUtils.isGlobalUnicast(addr.getAddress()))
			return;
		synchronized (originPairs) {
			originPairs.put(source, addr);
			if (originPairs.size() > 20) {
				originPairs.values().stream()
					.collect(Collectors.groupingBy(o -> o, Collectors.counting()))
					.entrySet().stream()
					.max((a,b) -> (int)(a.getValue() - b.getValue()))
					.ifPresent(e -> setConsensusAddress(e.getKey()));
			}
		}
				
	}
	
	private void setConsensusAddress(InetSocketAddress addr) {
		consensusExternalAddress = addr;
	}
	
	public InetSocketAddress getConsensusExternalAddress() {
		return consensusExternalAddress;
	}

	private void fillPipe(EnqueuedSend es) {
		pipeline.add(es);
		sel.writeEvent();
	}
		

	private void dispatchCall(RPCCall call, byte[] mtid) {
		MessageBase msg = call.getRequest();
		msg.setMTID(mtid);
		call.addListener(rpcListener);
		timeoutFilter.registerCall(call);
		EnqueuedSend es = new EnqueuedSend(msg);
		es.associatedCall = call;
		fillPipe(es);
	}

	/* (non-Javadoc)
	 * @see lbms.plugins.mldht.kad.RPCServerBase#sendMessage(lbms.plugins.mldht.kad.messages.MessageBase)
	 */
	public void sendMessage (MessageBase msg) {
		fillPipe(new EnqueuedSend(msg));
	}
	
	public ResponseTimeoutFilter getTimeoutFilter() {
		return timeoutFilter;
	}

	/*
	private void send(InetSocketAddress addr, byte[] msg) throws IOException {
		if (!sock.isClosed()) {
			DatagramPacket p = new DatagramPacket(msg, msg.length);
			p.setSocketAddress(addr);
			try {
				sock.send(p);
			} catch (IOException e) {
				if (sock.isClosed() || NetworkInterface.getByInetAddress(sock.getLocalAddress()) == null) {
					createSocket();
					sock.send(p);
				} else
				{
					throw e;
				}
			}

		}
	}*/

	private void doQueuedCalls () {
		while (call_queue.peek() != null && calls.size() < DHTConstants.MAX_ACTIVE_CALLS) {
			RPCCall c;

			if ((c = call_queue.poll()) == null)
				return;
			
			doCall(c);
		}
	}
	
	@Override
	public String toString() {
		StringBuilder b = new StringBuilder();
		b.append(getDerivedID()).append("\t").append("bind: ").append(getBindAddress()).append(" consensus: ").append(consensusExternalAddress).append('\n');
		b.append("rx: ").append(numReceived).append(" tx:").append(numSent).append(" active:").append(getNumActiveRPCCalls()).append(" baseRTT:").append(timeoutFilter.getStallTimeout()).append(" uptime:").append(Duration.between(startTime, Instant.now())).append('\n');
		return b.toString();
	}
	
	private class SocketHandler implements Selectable {
		
		DatagramChannel channel;
		SelectionKey key;

		private static final int WRITE_STATE_IDLE = 0;
		private static final int WRITE_STATE_WRITING = 2;
		private static final int WRITE_STATE_AWAITING_NIO_NOTIFICATION = 3;
		private static final int CLOSED = 4;
		
		private final AtomicInteger writeState = new AtomicInteger(WRITE_STATE_IDLE);
		
		public SocketHandler() {
			try {
				timeoutFilter.reset();
	
				channel = DatagramChannel.open();
				channel.configureBlocking(false);
				channel.setOption(StandardSocketOptions.SO_RCVBUF, 2*1024*1024);
				channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
				channel.bind(new InetSocketAddress(addr, port));
				dht.getConnectionManager().register(this);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		NIOConnectionManager connectionManager;

		@Override
		public void selectionEvent(SelectionKey key) throws IOException {
			// schedule async writes first before spending thread time on reads
			if (key.isValid() && key.isWritable()) {
				writeState.set(WRITE_STATE_IDLE);
				connectionManager.interestOpsChanged(this);
				DHT.getScheduler().execute(this::writeEvent);
			}
			
			if (key.isValid() && key.isReadable())
				readEvent();
			
		}
		
		private final ByteBuffer readBuffer = ByteBuffer.allocateDirect(DHTConstants.RECEIVE_BUFFER_SIZE);
		
		private void readEvent() throws IOException {
			
			while (true) {
				readBuffer.clear();
				InetSocketAddress soa =  (InetSocketAddress) channel.receive(readBuffer);
				if (soa == null)
					break;
				
				// * no conceivable DHT message is smaller than 10 bytes
				// * all DHT messages start with a 'd' for dictionary
				// * port 0 is reserved
				// -> immediately discard junk on the read loop, don't even allocate a buffer for it
				if (readBuffer.position() < 10 || readBuffer.get(0) != 'd' || soa.getPort() == 0)
					continue;
				if (throttle.isSpam(soa.getAddress()))
					continue;
				
				// copy from the read buffer since we hand off to another thread
				readBuffer.flip();
				ByteBuffer buf = ByteBuffer.allocate(readBuffer.limit()).put(readBuffer);
				buf.flip();
				
				DHT.getScheduler().execute(() -> {handlePacket(buf, soa);});
				numReceived++;
				stats.addReceivedBytes(buf.limit() + dht.getType().HEADER_LENGTH);
			}
		}
		
		public void writeEvent() {
			
			// simply assume nobody else is writing and attempt to do it
			// if it fails it's the current writer's job to double-check after releasing the write lock
			int currentState = WRITE_STATE_IDLE;
			
			if (writeState.compareAndSet(currentState, WRITE_STATE_WRITING)) {
				// we are now the exclusive writer for this socket
				
				while (true) {
					
					EnqueuedSend es = pipeline.poll();
					
					if (es == null)
						break;
					
					MessageBase msg = es.toSend;
					if (msg instanceof FindNodeRequest)
						System.out.println("FindNodeRequest is sent...");
					else if (msg instanceof AnnounceRequest)
						System.out.println("AnnounceRequest is sent...");
					else if (msg instanceof GetPeersRequest)
						System.out.println("GetPeersRequest is sent...");
					else if (msg instanceof PingRequest)
						System.out.println("PingRequest is sent...");
					
					try {
						ByteBuffer buf = es.getBuffer();
						
						int bytesSent = channel.send(buf, es.toSend.getDestination());
						
						if (bytesSent == 0) {
							pipeline.add(es);
							// wakeup -> updates selections -> will wait for write OP
							connectionManager.interestOpsChanged(this);
							
							writeState.set(WRITE_STATE_AWAITING_NIO_NOTIFICATION);
							return;
						}
						
						if (DHT.isLogLevelEnabled(LogLevel.Verbose)) {
							DHT.logVerbose("sent: " + prettyPrint(es.toSend.getBase())+ " to " + es.toSend.getDestination());
						}
						
						if (es.associatedCall != null)
							es.associatedCall.sent();
						
						stats.addSentMessageToCount(es.toSend);
						stats.addSentBytes(bytesSent + dht.getType().HEADER_LENGTH);
						if (DHT.isLogLevelEnabled(LogLevel.Debug))
							DHT.logDebug("RPC send message to " + es.toSend.getDestination() + " | "+ es.toSend.toString() + " | length: " +bytesSent);
					} catch (IOException e) {
						DHT.log(new IOException(addr+" -> "+es.toSend.getDestination(), e), LogLevel.Error);
						if (es.associatedCall != null) { // need to notify listeners
							es.associatedCall.sendFailed();
						}
						break;
					}
					
					numSent++;
				}
				
				// release claim on the socket
				writeState.set(WRITE_STATE_IDLE);
				
				// check if we might have to pick it up again due to races
				// schedule async to avoid infinite stacks
				if (pipeline.peek() != null)
					DHT.getScheduler().execute(this::writeEvent);

			
			}
			
	
		}
		
		@Override
		public void registrationEvent(NIOConnectionManager manager, SelectionKey key) throws IOException {
			connectionManager = manager;
			this.key = key;
			updateSelection();
		}
		
		@Override
		public SelectableChannel getChannel() {
			return channel;
		}
		
		public void close() throws IOException {
			if (writeState.get() == CLOSED)
				return;
			writeState.set(CLOSED);
			stop();
			connectionManager.deRegister(this);
		}
		
		@Override
		public void doStateChecks(long now) throws IOException {
			if (!channel.isOpen() || channel.socket().isClosed()) {
				close();
				return;
			}
		}
		
		public void updateSelection() {
			int ops = SelectionKey.OP_READ;
			if (writeState.get() == WRITE_STATE_AWAITING_NIO_NOTIFICATION)
				ops |= SelectionKey.OP_WRITE;
			key.interestOps(ops);
		}
	}

	private class EnqueuedSend {
		MessageBase toSend;
		RPCCall associatedCall;
		ByteBuffer buf;
		
		public EnqueuedSend(MessageBase msg) {
			toSend = msg;
			assert(toSend.getDestination() != null);
			decorateMessage();
		}
		
		private void decorateMessage() {
			if (toSend.getID() == null)
				toSend.setID(getDerivedID());
			
			// don't include IP on GET_PEER responses, they're already pretty heavy-weight
			if ((toSend instanceof PingResponse || toSend instanceof FindNodeResponse) && toSend.getPublicIP() == null) {
				toSend.setPublicIP(toSend.getDestination());
			}
		}
		
		ByteBuffer getBuffer() throws IOException {
			if (buf != null)
				return buf;
			try {
				return buf = ByteBuffer.wrap(toSend.encode(dht.getType().MAX_PACKET_SIZE));
			} catch (Exception e) {
				byte[] t = new byte[0];
				try {
					t = toSend.encode(4096);
				} catch(Exception e2) {
					
				}
				
				DHT.logError("encode failed for " + toSend.toString() + " 2nd encode attempt: (" + t.length + ") bytes. base map was:" + Utils.prettyPrint(toSend.getBase())  );
				
				
				throw new IOException(e) ;
			}
		}
	}
	
}
