/**
 * Copyright 2013-2019 Xia Jun(3979434@qq.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ***************************************************************************************
 *                                                                                     *
 *                        Website : http://www.farsunset.com                           *
 *                                                                                     *
 ***************************************************************************************
 */
package com.farsunset.cim.sdk.client;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.farsunset.cim.sdk.client.coder.CIMLogger;
import com.farsunset.cim.sdk.client.coder.ClientMessageDecoder;
import com.farsunset.cim.sdk.client.coder.ClientMessageEncoder;
import com.farsunset.cim.sdk.client.constant.CIMConstant;
import com.farsunset.cim.sdk.client.exception.SessionClosedException;
import com.farsunset.cim.sdk.client.model.HeartbeatRequest;
import com.farsunset.cim.sdk.client.model.HeartbeatResponse;
import com.farsunset.cim.sdk.client.model.Intent;
import com.farsunset.cim.sdk.client.model.Message;
import com.farsunset.cim.sdk.client.model.ReplyBody;
import com.farsunset.cim.sdk.client.model.SentBody;


/**
 * 连接服务端管理，cim核心处理类，管理连接，以及消息处理
 * 
 * @author 3979434@qq.com
 */
class CIMConnectorManager {

	private static CIMConnectorManager manager;
	
	private final int READ_BUFFER_SIZE = 2048;
	private final int WRITE_BUFFER_SIZE = 1024;

    private final AtomicBoolean CONNECTING_FLAG = new AtomicBoolean(false) ;
    
    private final CIMLogger LOGGER = CIMLogger.getLogger();
    
	private Selector selector;
	private SocketChannel socketChannel ;
	
	
    private ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
    
	private ExecutorService workerExecutor = Executors.newFixedThreadPool(1);
	private ExecutorService bossExecutor = Executors.newFixedThreadPool(1);
	private ExecutorService eventExecutor = Executors.newFixedThreadPool(1);

	
	private ClientMessageEncoder messageEncoder = new  ClientMessageEncoder();
	private ClientMessageDecoder messageDecoder = new  ClientMessageDecoder();
   
	private CIMConnectorManager() {
		makeNioConnector();
	}
	private void makeNioConnector() {
		try {
			 if(socketChannel == null || !socketChannel.isOpen()) {
				 socketChannel = SocketChannel.open();
		         socketChannel.configureBlocking(false);
	             socketChannel.setOption(StandardSocketOptions.SO_RCVBUF,READ_BUFFER_SIZE);
	             socketChannel.setOption(StandardSocketOptions.SO_SNDBUF, WRITE_BUFFER_SIZE);
	             socketChannel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
	             socketChannel.setOption(StandardSocketOptions.TCP_NODELAY,true);
			 }
			
             if(selector == null || !selector.isOpen()) {
            	 selector = Selector.open();
             }
             
             selector.wakeup(); 
		     socketChannel.register(selector, SelectionKey.OP_CONNECT);

		}catch(Exception ignore) {}
	 
	}
	
	public synchronized static CIMConnectorManager getManager() {
		
		if (manager == null) {
			manager = new CIMConnectorManager();
		}
		
		return manager;

	}

	public void connect(final String host, final int port) {
 
		 
		boolean isConnected = isConnected();
		if (CONNECTING_FLAG.get() || isConnected) {
			return;
		}
		
		CONNECTING_FLAG.set(true);
		
		if(!socketChannel.isOpen() ||!selector.isOpen()) {
			makeNioConnector();
		} 
		
		workerExecutor.execute(new Runnable() {
			@Override
			public void run() {
				
	            LOGGER.startConnect(host, port);
				
				CIMCacheManager.getInstance().putBoolean(CIMCacheManager.KEY_CIM_CONNECTION_STATE, false);
				
 				try {
 					
 					socketChannel.connect(new InetSocketAddress(host, port));
 					
 					while (socketChannel.isOpen()) {
 						
 						selector.select();
 						
 						if(!selector.isOpen()) {
 							break;
 						}
 						
 						for(SelectionKey key : selector.selectedKeys()){
 							
 							if((key.interestOps() & SelectionKey.OP_CONNECT) == SelectionKey.OP_CONNECT && socketChannel.finishConnect()) {
 								handelConnectedEvent();
 		                		continue;
 							}
 							
 							if((key.interestOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ) {
 								handelSocketReadEvent();
 							}
 						}
 		            }
 					
				}catch(ConnectException ignore){
					handleConnectAbortedEvent();
				}catch(IllegalArgumentException ignore){
					handleConnectAbortedEvent();
				}catch(IOException ignore) {
					handelDisconnectedEvent();
				}catch(ClosedSelectorException ignore) {}
			}
		});
	}
	 
 
	
	private void handelDisconnectedEvent() {
		CONNECTING_FLAG.set(false);
		closeSession();
	}
	
    private void handleConnectAbortedEvent() {
		
		CONNECTING_FLAG.set(false);

		long interval = CIMConstant.RECONN_INTERVAL_TIME - (5 * 1000 - new Random().nextInt(15 * 1000));
		
		LOGGER.connectFailure(interval);
		
		Intent intent = new Intent();
		intent.setAction(CIMConstant.IntentAction.ACTION_CONNECTION_FAILED);
		intent.putExtra("interval", interval);
		sendBroadcast(intent);

	}
	 
	private void handelConnectedEvent() throws IOException {
		
		CONNECTING_FLAG.set(false);
 		socketChannel.register(selector, SelectionKey.OP_READ);
		sessionCreated();
		
	}
	
	private void handelSocketReadEvent() throws IOException   {
		
		int result = 0;
		
		while((result = socketChannel.read(readBuffer)) > 0) {
			if(readBuffer.position() == readBuffer.capacity()) {
				extendByteBuffer();
			}
		}
		
	    if(result == -1) {
	    	closeSession();
	    	return;
	    }
		
	    readBuffer.position(0);
	     
		Object message = messageDecoder.doDecode(readBuffer);
		
		if(message == null) {
			return;
		}
		
		LOGGER.messageReceived(socketChannel,message);

		if(isHeartbeatRequest(message)) {

			send(getHeartbeatResponse());
			
			return;
		}
		
		this.messageReceived(message);
	}
	
	
	private void extendByteBuffer() {
		
		ByteBuffer newBuffer = ByteBuffer.allocate(readBuffer.capacity() + READ_BUFFER_SIZE / 2);
		readBuffer.position(0);
		newBuffer.put(readBuffer);
		
		readBuffer.clear();
		readBuffer = newBuffer;
	}
    
	
	public void send(final SentBody body) {
		
		bossExecutor.execute(new Runnable() {
			
			@Override
			public void run() {
				boolean isSuccessed = false;
				String exceptionName = SessionClosedException.class.getSimpleName();
				if (isConnected()) {
					
					try {
						ByteBuffer buffer =  messageEncoder.encode(body);
						int result = 0;
						while(buffer.hasRemaining()){
							result += socketChannel.write(buffer);   
						}
						isSuccessed = result > 0;
					} catch (IOException e) {
						exceptionName = e.getClass().getSimpleName();
						closeSession();
					}
					 
				}
				
				if (!isSuccessed) {
					Intent intent = new Intent();
					intent.setAction(CIMConstant.IntentAction.ACTION_SENT_FAILED);
					intent.putExtra(Exception.class.getName(), exceptionName);
					intent.putExtra(SentBody.class.getName(), body);
					sendBroadcast(intent);
				}else {
					messageSent(body);
				}
			}
		});
		
	}
	
	
   public void send(final HeartbeatResponse body) {
		
		bossExecutor.execute(new Runnable() {
			@Override
			public void run() {
				try {
					 socketChannel.write(messageEncoder.encode(body));
					 messageSent(body);
				} catch (IOException ignore) {
					 closeSession();
				}
			}
		});
		
	}


	public void sessionCreated() {
		
		LOGGER.sessionCreated(socketChannel);
		
		Intent intent = new Intent();
		intent.setAction(CIMConstant.IntentAction.ACTION_CONNECTION_SUCCESSED);
		sendBroadcast(intent);

	}

	public void sessionClosed() {

 		LOGGER.sessionClosed(socketChannel);

		readBuffer.clear();
		
		if(readBuffer.capacity() > READ_BUFFER_SIZE) {
			readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
		}
		
		closeSelector();
		
		Intent intent = new Intent();
		intent.setAction(CIMConstant.IntentAction.ACTION_CONNECTION_CLOSED);
		sendBroadcast(intent);
		
	}
 
	public void messageReceived(Object obj) {

		if (obj instanceof Message) {

			Intent intent = new Intent();
			intent.setAction(CIMConstant.IntentAction.ACTION_MESSAGE_RECEIVED);
			intent.putExtra(Message.class.getName(), (Message) obj);
			sendBroadcast(intent);

		}
		if (obj instanceof ReplyBody) {

			Intent intent = new Intent();
			intent.setAction(CIMConstant.IntentAction.ACTION_REPLY_RECEIVED);
			intent.putExtra(ReplyBody.class.getName(), (ReplyBody) obj);
			sendBroadcast(intent);
		}
	}

	
	public void messageSent(Object message) {
		
		LOGGER.messageSent(socketChannel, message);
		
		if (message instanceof SentBody) {
			Intent intent = new Intent();
			intent.setAction(CIMConstant.IntentAction.ACTION_SENT_SUCCESSED);
			intent.putExtra(SentBody.class.getName(), (SentBody) message);
			sendBroadcast(intent);
		}
	}

	public HeartbeatResponse getHeartbeatResponse() {
		return HeartbeatResponse.getInstance();
	}

	public boolean isHeartbeatRequest(Object data) {
		return data instanceof HeartbeatRequest;
	}
 
    public void destroy() {
		
		closeSession();
		closeSelector();

	}

	public boolean isConnected() {
		return socketChannel != null && socketChannel.isConnected();
	}

	public void closeSession() {
		try {
			socketChannel.close();
		} catch (IOException ignore) {
		}finally {
			 this.sessionClosed();
		}
	}

	public void closeSelector() {
		try {
			selector.close();
		} catch (IOException ignore) {}
	}

	
	private void sendBroadcast(final Intent intent) {
		eventExecutor.execute(new Runnable() {
			@Override
			public void run() {
				CIMEventBroadcastReceiver.getInstance().onReceive(intent);
			}
		});
	}
	 
}