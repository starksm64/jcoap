/* Copyright [2011] [University of Rostock]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *****************************************************************************/

package org.ws4d.coap.connection;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.SimpleLayout;
import org.ws4d.coap.interfaces.CoapChannel;
import org.ws4d.coap.interfaces.CoapChannelManager;
import org.ws4d.coap.interfaces.CoapClient;
import org.ws4d.coap.interfaces.CoapClientChannel;
import org.ws4d.coap.interfaces.CoapMessage;
import org.ws4d.coap.interfaces.CoapSocketHandler;
import org.ws4d.coap.messages.AbstractCoapMessage;
import org.ws4d.coap.messages.CoapPacketType;
import org.ws4d.coap.tools.TimeoutHashMap;

public class BasicCoapSocketHandler implements CoapSocketHandler {
    /**
     * @author Christian Lerche <christian.lerche@uni-rostock.de>
     * @author Nico Laum <nico.laum@uni-rostock.de>
     */
	private final static Logger logger = Logger.getLogger(BasicCoapSocketHandler.class); 
    protected WorkerThread workerThread = null;
    protected HashMap<ChannelKey, CoapChannel> channels = new HashMap<ChannelKey, CoapChannel>();
    
    private CoapChannelManager channelManager = null;
    private DatagramChannel dgramChannel = null;
    
	public static final int UDP_BUFFER_SIZE = 66000; // max UDP size = 65535 

    byte[] sendBuffer = new byte[UDP_BUFFER_SIZE];
    
    private int localPort;

    public BasicCoapSocketHandler(CoapChannelManager channelManager, int port) throws IOException {
        logger.addAppender(new ConsoleAppender(new SimpleLayout()));
        // ALL | DEBUG | INFO | WARN | ERROR | FATAL | OFF:
        logger.setLevel(Level.WARN);
    	
    	
    	this.channelManager = channelManager;
        dgramChannel = DatagramChannel.open();
       	dgramChannel.socket().bind(new InetSocketAddress(port)); //port can be 0, then a free port is chosen 
        this.localPort = dgramChannel.socket().getLocalPort();
        dgramChannel.configureBlocking(false);
       
        workerThread = new WorkerThread();
        workerThread.start();
    }
    
    public BasicCoapSocketHandler(CoapChannelManager channelManager) throws IOException {
        this(channelManager, 0);
    }
    
    protected class WorkerThread extends Thread {
        Selector selector = null;

        /* contains all received message keys to detect duplications 
         * TODO: implement it like this: map with sender: last messageID */
        TimeoutHashMap<MessageKey, Boolean> duplicateMap = new TimeoutHashMap<MessageKey, Boolean>(CoapMessage.ACK_RST_RETRANS_TIMEOUT_MS);
        /* contains all messages that (possibly) needs to be retransmitted (ACK, RST)*/
        TimeoutHashMap<MessageKey, CoapMessage> retransMsgMap = new TimeoutHashMap<MessageKey, CoapMessage>(CoapMessage.ACK_RST_RETRANS_TIMEOUT_MS);
        /* contains all messages that are not confirmed yet (CON)*/
        TimeoutHashMap<MessageKey, CoapMessage> nonConfirmedMsgMap = new TimeoutHashMap<MessageKey, CoapMessage>(CoapMessage.ACK_RST_RETRANS_TIMEOUT_MS);
        
		private PriorityQueue<TimeoutObject<MessageKey>> timeoutQueue = new PriorityQueue<TimeoutObject<MessageKey>>(); 

		public ConcurrentLinkedQueue<CoapMessage> sendBuffer = new ConcurrentLinkedQueue<CoapMessage>();
		
		
		/* Contains all sent messages sorted by message ID */
		long startTime;
		static final int POLLING_INTERVALL = 10000;
		
		ByteBuffer dgramBuffer;

		public WorkerThread() {
			dgramBuffer = ByteBuffer.allocate(UDP_BUFFER_SIZE); 
		    startTime = System.currentTimeMillis();
		    try {
				selector = Selector.open();
				dgramChannel.register(selector, SelectionKey.OP_READ);
			} catch (IOException e1) {
				e1.printStackTrace();
			}
		}

		public void close() {
	        if (channels != null)
	            channels.clear();
	        try {
				dgramChannel.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
	        /* TODO: wake up thread and kill it*/
		}
		
		@Override
		public void run() {
		    logger.log(Level.INFO, "Receive Thread started.");
			long waitFor = POLLING_INTERVALL;
			InetSocketAddress addr = null;
			
			while (dgramChannel != null) {
				/* send all messages in the send buffer */
				sendBufferedMessages();

				/* handle incoming packets */
				dgramBuffer.clear();
				try {
					addr = (InetSocketAddress) dgramChannel.receive(dgramBuffer);
				} catch (IOException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
				if (addr != null){
					logger.log(Level.INFO, "handle incomming msg");
					handleIncommingMessage(dgramBuffer, addr);
				}
				
				/* handle timeouts */
				waitFor = handleTimeouts();
				
				/* TODO: find a good strategy when to update the timeout maps */
		        duplicateMap.update();
		        retransMsgMap.update();
		        nonConfirmedMsgMap.update();
		        
		        /* wait until
		         * 1. selector.wakeup() is called by sendMessage()
		         * 2. incomming packet
		         * 3. timeout */
				try {
					/*FIXME: don't make a select, when something is in the sendQueue, otherwise the packet will be sent after some delay
					 * move this check and the select to a critical section */
					selector.select(waitFor);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		
		protected synchronized void addMessageToSendBuffer(CoapMessage msg){
			sendBuffer.add(msg);
			/* send immediately */
			selector.wakeup();
		}
		
		private void sendBufferedMessages(){
			CoapMessage msg = sendBuffer.poll();
			while(msg != null){
				sendMsg(msg);
				msg = sendBuffer.poll();
			}
		}

		private void handleIncommingMessage(ByteBuffer buffer, InetSocketAddress addr) {
			CoapMessage msg;
			try {
				/* TODO drop invalid messages (invalid version, type etc.) */
				 msg = AbstractCoapMessage.parseMessage(buffer.array(), buffer.position());
			} catch (Exception e) {
				logger.warn("Received Invalid Message: Message dropped!");
				return;
			}
			
			CoapPacketType packetType = msg.getPacketType();
			int msgID = msg.getMessageID();
			
			/* check for duplicates */
			MessageKey msgKey = new MessageKey(msgID, addr.getAddress(), addr.getPort());
			if (duplicateMap.get(msgKey) != null){
				/* detected retransmission
				 * - sendMessage checks if the message is null
				 * - for CON: send again corresponding ACK or RST, they are saved in the retransMsgMap when they are send */ 
				/* TODO: send message immediately, there is no need to buffer the message*/
				sendMsg((CoapMessage) retransMsgMap.get(msgKey));
				return;
			}
			duplicateMap.put(msgKey, true);
			
			
			/* get the channel or create a new one */
			CoapChannel channel = getChannel(addr.getAddress(),	addr.getPort());
			if (channel == null){
				if ((packetType == CoapPacketType.CON)|| (packetType == CoapPacketType.NON)) {
					/* CON or NON create a new channel or reset connection */
					try {
						channel = channelManager.createServerChannel(BasicCoapSocketHandler.this, msg, addr.getAddress(), addr.getPort());
						addChannel(channel);
					} catch (Exception e) {
						/* not a valid request */
						logger.warn("Create Channel failed (invalid request)");
						return;
					}
					
					if (channel == null){
						logger.log(Level.INFO, "Reset connection...");
						if (packetType == CoapPacketType.CON) {
							/* server doesn't accept the connection -->
							 * TODO implement Reset Connection (Send RST) */
							throw new IllegalStateException("resetting a connection is not implemented yet"); 
						}
						return;
					}
					logger.log(Level.INFO, "Created new server channel...");
				} else {
					/* Ignore ACK and RST if no channel exists */
					return;
				}
			} 
			
			
			msg.setChannel(channel);
			
//			if ((packetType == CoapPacketType.ACK)|| (packetType == CoapPacketType.RST)) {
			if (packetType != CoapPacketType.CON) {
				/* confirm message by removing it from the non confirmedMsgMap*/
				/* Corresponding to coap-09 the server should be aware of a NON as answer to a CON*/
				nonConfirmedMsgMap.remove(msgKey);
			}

			channel.newIncommingMessage(msg);
		}
		
		private long handleTimeouts(){
			long nextTimeout = POLLING_INTERVALL; 
			
			while (true){
				TimeoutObject<MessageKey> tObj = timeoutQueue.peek();
				if (tObj == null){
					/* timeout queue is empty */
					nextTimeout = POLLING_INTERVALL;
					break;
				}
				
				nextTimeout = tObj.expires - System.currentTimeMillis();
				if (nextTimeout > 0){
					/* timeout not expired */
					break;
				}
				/* timeout expired, sendMessage will send the message and create a new timeout 
				 * if the message was already confirmed, nonConfirmedMsgMap.get() will return null */
				timeoutQueue.poll();
				MessageKey msgKey = tObj.object;
				sendMsg((CoapMessage) nonConfirmedMsgMap.get(msgKey));
			}
			return nextTimeout;
		}
		
		private void sendMsg(CoapMessage msg) {
			if (msg == null){
				return;
			}
			
			CoapPacketType packetType = msg.getPacketType();
			InetAddress inetAddr = msg.getChannel().getRemoteAddress();
			int port = msg.getChannel().getRemotePort();
			MessageKey msgKey = new MessageKey(msg.getMessageID(), inetAddr, port);
			
			if (packetType == CoapPacketType.CON){
				if(msg.maxRetransReached()){
					/* the connection is broken */
					nonConfirmedMsgMap.remove(msgKey);
					msg.getChannel().lostConnection(true, false);
					return;
				}
				msg.incRetransCounterAndTimeout();
				nonConfirmedMsgMap.put(msgKey, msg);
				TimeoutObject<MessageKey> tObj = new TimeoutObject<MessageKey>(msgKey, msg.getTimeout() + System.currentTimeMillis());
				timeoutQueue.add(tObj);
			}
			
			if (packetType == CoapPacketType.ACK || packetType == CoapPacketType.RST){
				/* save this type of messages for a possible retransmission */
				retransMsgMap.put(msgKey, msg);
			}
			
			/* send message*/
			ByteBuffer buf = ByteBuffer.wrap(msg.serialize());
			/*TODO: check if serilization could fail... then do not put it to any Map!*/
		    try {
		    	dgramChannel.send(buf, new InetSocketAddress(inetAddr, port));
		        logger.log(Level.INFO, "Send Msg with ID: " + msg.getMessageID());
		    } catch (IOException e) {
		    	e.printStackTrace();
		    }
		}

		private CoapChannel getChannel(InetAddress inetAddr, int port) {
			return channels.get(new ChannelKey(inetAddr, port));
		}
	}
    
    private class MessageKey{
		public int msgID;
		public InetAddress inetAddr;
		public int port;
		
		public MessageKey(int msgID, InetAddress inetAddr, int port) {
			super();
			this.msgID = msgID;
			this.inetAddr = inetAddr;
			this.port = port;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + getOuterType().hashCode();
			result = prime * result
					+ ((inetAddr == null) ? 0 : inetAddr.hashCode());
			result = prime * result + msgID;
			result = prime * result + port;
			return result;
		}
		
		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			MessageKey other = (MessageKey) obj;
			if (!getOuterType().equals(other.getOuterType()))
				return false;
			if (inetAddr == null) {
				if (other.inetAddr != null)
					return false;
			} else if (!inetAddr.equals(other.inetAddr))
				return false;
			if (msgID != other.msgID)
				return false;
			if (port != other.port)
				return false;
			return true;
		}
		private BasicCoapSocketHandler getOuterType() {
			return BasicCoapSocketHandler.this;
		}
    }
    
    private class ChannelKey{
		public InetAddress inetAddr;
		public int port;
		
		public ChannelKey(InetAddress inetAddr, int port) {
			this.inetAddr = inetAddr;
			this.port = port;
		}
		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + getOuterType().hashCode();
			result = prime * result
					+ ((inetAddr == null) ? 0 : inetAddr.hashCode());
			result = prime * result + port;
			return result;
		}
		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			ChannelKey other = (ChannelKey) obj;
			if (!getOuterType().equals(other.getOuterType()))
				return false;
			if (inetAddr == null) {
				if (other.inetAddr != null)
					return false;
			} else if (!inetAddr.equals(other.inetAddr))
				return false;
			if (port != other.port)
				return false;
			return true;
		}
		private BasicCoapSocketHandler getOuterType() {
			return BasicCoapSocketHandler.this;
		}
		
    }
    
    private class TimeoutObject<T> implements Comparable<TimeoutObject>{
    	private long expires;
    	private T object;
    	
    	public TimeoutObject(T object, long expires) {
			this.expires = expires;
			this.object = object;
		}
    	
		public T getObject() {
			return object;
		}

		public int compareTo(TimeoutObject o){
    		 return (int) (this.expires - o.expires);
    	}
    }
    

	private void addChannel(CoapChannel channel) {
        channels.put(new ChannelKey(channel.getRemoteAddress(), channel.getRemotePort()), channel);
    }
	

	@Override
    public int getLocalPort() {
		return localPort;
	}

	@Override
    public void removeChannel(CoapChannel channel) {
        channels.remove(new ChannelKey(channel.getRemoteAddress(), channel.getRemotePort()));
    }

    @Override
    public void close() {
    	workerThread.close();
    }

    // @Override
    // public boolean isOpen() {
    // if (socket!=null && socket.isBound() && socket.isConnected())
    // return true;
    // else
    // return false;
    // }

    /**
     * @param message The message to be sent. This method will give the message
     *            a new message id!
     */
    @Override
    public void sendMessage(CoapMessage message) {
        if (workerThread != null) {
            workerThread.addMessageToSendBuffer(message);
        }
    }

    //
    // @Override
    // public int sendRequest(CoapMessage request) {
    // sendMessage(request);
    // return request.getMessageID();
    // }
    //
    // @Override
    // public void sendResponse(CoapResponse response) {
    // sendMessage(response);
    // }
    //
    // @Override
    // public void establish(DatagramSocket socket) {
    //
    // }
    //
    // @Override
    // public void unregisterResponseListener(CoapResponseListener
    // responseListener) {
    // coapResponseListeners.remove(responseListener);
    // }

    @Override
    public CoapClientChannel connect(CoapClient client, InetAddress remoteAddress,
            int remotePort) {
    	if (client == null){
    		return null;
    	}

    	if (channels.containsKey(new ChannelKey(remoteAddress, remotePort))){
    		/* channel already exists */
    		return null;
    	}
    	
    	CoapClientChannel channel = new BasicCoapClientChannel(this, client, remoteAddress, remotePort);
    	
    	
        addChannel(channel);
        return channel;
    }

    @Override
    public CoapChannelManager getChannelManager() {
        return this.channelManager;
    }

}
