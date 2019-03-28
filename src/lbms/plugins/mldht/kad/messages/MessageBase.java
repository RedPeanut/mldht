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
package lbms.plugins.mldht.kad.messages;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;

import lbms.plugins.mldht.kad.DHT;
import lbms.plugins.mldht.kad.DHTConstants;
import lbms.plugins.mldht.kad.Key;
import lbms.plugins.mldht.kad.RPCCall;
import lbms.plugins.mldht.kad.RPCServer;
import lbms.plugins.mldht.kad.utils.AddressUtils;
import the8472.bencode.BEncoder;

/**
 * Base class for all RPC messages.
 *
 * @author Damokles
 */
public abstract class MessageBase {
	
	public static final String	VERSION_KEY = "v";
	public static final String	TRANSACTION_KEY = "t";
	public static final String  EXTERNAL_IP_KEY = "ip";

	protected byte[]			mtid;
	protected Method			method;
	protected Type				type;
	protected Key				id;
	
	// TODO: unify as remoteAddress
	protected InetSocketAddress	origin;
	protected InetSocketAddress destination;

	// for outgoing messages this is the IP we tell them
	// for incoming messages this is the IP they told us
	protected InetSocketAddress publicIP;

	protected String			version;
	protected RPCServer			srv;
	protected RPCCall			associatedCall;

	public MessageBase (byte[] mtid, Method m, Type type) {
		this.mtid = mtid;
		this.method = m;
		this.type = type;
	}

	/**
	 * When this message arrives this function will be called upon the DHT.
	 * The message should then call the appropriate DHT function (double dispatch)
	 * @param dh_table Pointer to DHT
	 */
	public void apply (DHT dh_table) {
	}

	/**
	 * BEncode the message.
	 * @return Data array
	 */
	public byte[] encode(int capacity) throws IOException
	{
		ByteBuffer buf = new BEncoder().encode(getBase(),capacity);
		byte out[] = new byte[buf.remaining()];
		buf.get(out);
		return out;
	}
	
	public Map<String, Object> getBase() {
		Map<String, Object> base = new TreeMap<String, Object>();
		Map<String, Object> inner = getInnerMap();
		if (inner != null)
			base.put(getType().innerKey(), inner);
		
		assert(mtid != null);
		// transaction ID
		base.put(TRANSACTION_KEY, mtid);
		// version
		base.put(VERSION_KEY, DHTConstants.getVersion());
		
	
		// message type
		base.put(Type.TYPE_KEY, getType().getRPCTypeName());
		// message method if we're a request
		if (getType() == Type.REQ_MSG)
			base.put(getType().getRPCTypeName(), getMethod().getRPCName());
		if (publicIP != null && getType() == Type.RSP_MSG)
			base.put(EXTERNAL_IP_KEY, AddressUtils.packAddress(publicIP));

		return base;
	}
	
	public Map<String, Object> getInnerMap() {
		return null;
	}


	public void setOrigin (InetSocketAddress o) {
		origin = o;
	}

	public InetSocketAddress getOrigin () {
		return origin;
	}

	// where the message was sent to
	public void setDestination (InetSocketAddress o) {
		destination = o;
	}

	/// Get the origin
	public InetSocketAddress getDestination () {
		return destination;
	}

	/// Get the MTID
	public byte[] getMTID () {
		return mtid;
	}

	/// Set the MTID
	public void setMTID (byte[] m) {
		mtid = m;
	}
	
	public InetSocketAddress getPublicIP() {
		return publicIP;
	}

	public void setPublicIP(InetSocketAddress publicIP) {
		this.publicIP = publicIP;
	}


	public String getVersion () {
    	return version;
    }

	public void setVersion (String version) {
    	this.version = version;
    }
	
	public void setServer(RPCServer srv) {
		this.srv = srv;
	}
	
	public RPCServer getServer() {
		return srv;
	}
	
	public void setID(Key id) {
		this.id = id;
	}

	/// Get the id of the sender
	public Key getID () {
		return id;
	}
	
	public void setAssociatedCall(RPCCall associatedCall) {
		this.associatedCall = associatedCall;
	}
	
	public RPCCall getAssociatedCall() {
		return associatedCall;
	}

	/// Get the type of the message
	public Type getType () {
		return type;
	}

	/// Get the message it's method
	public Method getMethod () {
		return method;
	}
	
	@Override
	public String toString() {
		return " Method:" + method + " Type:" + type + " MessageID:" + (mtid != null ? new String(mtid) : null) + (version != null ? " version:"+version : "")+"  ";
	}

	public static enum Type {
		REQ_MSG {
			String innerKey() {	return "a";	}
			String getRPCTypeName() { return "q"; }
		}, RSP_MSG {
			String innerKey() {	return "r";	}
			String getRPCTypeName() { return "r"; }
		}, ERR_MSG {
			String getRPCTypeName() { return "e"; }
			String innerKey() {	return "e";	}
		}, INVALID;
		
		String innerKey() {
			return null;
		}
		
		String getRPCTypeName()	{
			return null;
		}
		
		public static final String TYPE_KEY = "y";
	};

	public static enum Method {
		PING, FIND_NODE, GET_PEERS, ANNOUNCE_PEER, UNKNOWN;
		
		String getRPCName()	{
			return name().toLowerCase();						
		}
	};
}
