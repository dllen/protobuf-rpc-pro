/**
 *   Copyright 2010 Peter Klauser
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.googlecode.protobuf.pro.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jboss.netty.channel.Channel;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.googlecode.protobuf.pro.stream.logging.StreamLogger;
import com.googlecode.protobuf.pro.stream.server.PullHandler;
import com.googlecode.protobuf.pro.stream.server.PushHandler;
import com.googlecode.protobuf.pro.stream.wire.StreamProtocol.Chunk;
import com.googlecode.protobuf.pro.stream.wire.StreamProtocol.ChunkTypeCode;
import com.googlecode.protobuf.pro.stream.wire.StreamProtocol.CloseNotification;
import com.googlecode.protobuf.pro.stream.wire.StreamProtocol.Parameter;
import com.googlecode.protobuf.pro.stream.wire.StreamProtocol.PullRequest;
import com.googlecode.protobuf.pro.stream.wire.StreamProtocol.PushRequest;

/**
 *  
 * @author Peter Klauser
 * 
 */
public class StreamingServer<E extends Message, F extends Message> {

	private static Log log = LogFactory.getLog(StreamingServer.class);

	private final Map<Integer, TransferState> pendingTransferMap = new ConcurrentHashMap<Integer, TransferState>();

	private final PeerInfo serverInfo;
	private final PullHandler<E> pullHandler;
	private final PushHandler<F> pushHandler;
	private final StreamLogger streamLogger;
	
	private final int chunkSize;

	private Channel channel;
	private PeerInfo clientInfo;
	
	private ThreadPoolExecutor pullExecutorService;
	
	public StreamingServer(PeerInfo serverInfo, PullHandler<E> pullHandler, PushHandler<F> pushHandler, StreamLogger streamLogger, int chunkSize ) {
		this.serverInfo = serverInfo;
		this.pullHandler = pullHandler;
		this.pushHandler = pushHandler;
		this.streamLogger = streamLogger;
		this.chunkSize = chunkSize;
		
		this.pullExecutorService = new ThreadPoolExecutor(0, 10, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(10));
		this.pullExecutorService.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
	}

	public void handleOpen(PeerInfo clientInfo, Channel channel ) {
		this.clientInfo = clientInfo;
		this.channel = channel;
		if ( log.isDebugEnabled() ) {
			log.debug("handleOpen for " + clientInfo);
		}
	}
	
	@SuppressWarnings("unchecked")
	public void pushRequest(PushRequest pushRequest) {
		long startTS = System.currentTimeMillis();
		int correlationId = pushRequest.getCorrelationId();

		if ( log.isDebugEnabled() ) {
			log.debug("Received ["+pushRequest.getCorrelationId()+"]PushRequest.");
		}

		if (pendingTransferMap.containsKey(correlationId)) {
			throw new IllegalStateException("correlationId " + correlationId
					+ " already registered as PendingTransfer.");
		}
		F requestPrototype = pushHandler.getPrototype();

		F request = null;
		try {
			request = (F)requestPrototype.newBuilderForType()
					.mergeFrom(pushRequest.getRequestProto()).build();

		} catch (InvalidProtocolBufferException e) {
			String errorMessage = "Invalid Request Protobuf";

			log.warn(errorMessage, e);
			channel.close();
			return;
		}
		
		PushIn transferIn =  new PushIn(correlationId,channel);
		TransferState pendingTransfer = new TransferState(startTS, request, transferIn);
		registerPendingRequest(correlationId, pendingTransfer);
		pushHandler.init(request, transferIn);
	}

	@SuppressWarnings("unchecked")
	public void pullRequest(PullRequest pullRequest) {
		long startTS = System.currentTimeMillis();
		int correlationId = pullRequest.getCorrelationId();

		if ( log.isDebugEnabled() ) {
			log.debug("Received ["+pullRequest.getCorrelationId()+"]PullRequest.");
		}

		if (pendingTransferMap.containsKey(correlationId)) {
			throw new IllegalStateException("correlationId " + correlationId
					+ " already registered as PendingTransfer.");
		}

		E requestPrototype = pullHandler.getPrototype();

		E request = null;
		try {
			request = (E)requestPrototype.newBuilderForType()
					.mergeFrom(pullRequest.getRequestProto()).build();

		} catch (InvalidProtocolBufferException e) {
			String errorMessage = "Invalid Request Protobuf";

			log.warn(errorMessage, e);
			channel.close();
			return;
		}
		
		TransferOut transferOut =  new TransferOut(correlationId,chunkSize,channel);
		TransferState pendingTransfer = new TransferState(startTS, request, transferOut);
		registerPendingRequest(correlationId, pendingTransfer);

		PullWorker worker = new PullWorker(pendingTransfer);
		pullExecutorService.execute(worker);
	}

	void handlePullComplete(int correlationId) {
		TransferState state = removePendingTransfer(correlationId);
		if (state != null) {
			if ( log.isDebugEnabled() ) {
				log.debug("Pull Transfer complete.");
			}
//TODO log			
		}
	}
	
	public void pushChunk(Chunk chunk) {
		int correlationId = chunk.getCorrelationId();

		if ( log.isDebugEnabled() ) {
			log.debug("Received ["+chunk.getCorrelationId()+":"+chunk.getSeqNo()+"]PushChunk. " + chunk.getChunkType());
		}

		TransferState state = lookupPendingTransfer(correlationId);
		if ( state != null ) {
			PushIn transferIn = state.getPushIn();
			if ( transferIn == null ) {
				throw new IllegalStateException("TransferState missing transferIn");
			}
			if ( chunk.getParameterCount() > 0 ) {
				for( Parameter parameter : chunk.getParameterList() ) {
					transferIn.provideParameter(parameter.getName(), parameter.getValue());
				}
			}
			transferIn.setData(chunk.getPayload());
			pushHandler.data(state.getPushRequest(), transferIn);
			if ( ChunkTypeCode.END == chunk.getChunkType() ) {
				removePendingTransfer(correlationId);
				if ( transferIn.setClosed() ) {
					pushHandler.end(state.getPushRequest(), transferIn);
				}
			}
		} else {
			// this can happen only under race conditions with close
			if ( log.isDebugEnabled() ) {
				log.debug("No PendingTransferState found for correlationId " + chunk.getCorrelationId());
			}
		}
	}
	
	
	/**
	 * A server can receive closeNotification if the client
	 * closes the Pull's TransferIn before the server has
	 * sent all data to the TransferOut on the server side.
	 * 
	 * @param closeNotification
	 */
	public void closeNotification(CloseNotification closeNotification) {
		int correlationId = closeNotification.getCorrelationId();

		TransferState state = removePendingTransfer(correlationId);
		if (state != null) {
			// we only issue one cancel to the Executor
			if ( log.isDebugEnabled() ) {
				log.debug("Received ["+closeNotification.getCorrelationId()+"]CloseNotification.");
			}
			TransferOut transferOut = state.getPullOut();
			if ( transferOut == null ) {
				throw new IllegalStateException("TransferState missing transferOut");
			}
			transferOut.handleClosure();
		}
	}

	@Override
	public String toString() {
		return "StreamingServer[" + serverInfo +"]";
	}
	
	/**
	 * End any on going Transfers for this client.
	 */
	public void handleClosure() {
		if ( log.isDebugEnabled() ) {
			log.debug("handleClosure for " + clientInfo);
		}
		if ( clientInfo == null ) {
			//handleOpen never called
			return;
		}
		List<Integer> pendingTransferIds = new ArrayList<Integer>();
		pendingTransferIds.addAll(pendingTransferMap.keySet());
		do {
			for( Integer correlationId : pendingTransferIds ) {
				TransferState state = pendingTransferMap.remove(correlationId);
				if (state != null) {
					// we only issue one cancel to the Executor
					if ( log.isDebugEnabled() ) {
						log.debug("Force closure ["+correlationId+"].");
					}
					PushIn transferIn = state.getPushIn();
					if ( transferIn != null ) {
						if ( transferIn.setClosed() ) {
							pushHandler.end(state.getPushRequest(), transferIn);
						}
					}
					TransferOut transferOut = state.getPullOut();
					if ( transferOut != null ) {
						transferOut.handleClosure();
					}
				}
			}
		} while( pendingTransferMap.size() > 0 );
		
		// force shutdown the pull executor for this server channel
		pullExecutorService.shutdownNow();
	}
	
	protected void doLog( PeerInfo clientInfo, TransferState state, Message request, String errorMessage, Map<String,String> parameters ) {
		if ( streamLogger != null ) {
			streamLogger.logTransfer( clientInfo, serverInfo, request, errorMessage, state.getCorrelationId(), parameters, state.getStartTimestamp(), System.currentTimeMillis());
		}
	}
	
	private void registerPendingRequest(int seqId, TransferState state) {
		if (pendingTransferMap.containsKey(seqId)) {
			throw new IllegalArgumentException("State already registered");
		}
		pendingTransferMap.put(seqId, state);
	}

	private TransferState removePendingTransfer(int seqId) {
		return pendingTransferMap.remove(seqId);
	}

	private TransferState lookupPendingTransfer(int seqId) {
		return pendingTransferMap.get(seqId);
	}

	public class PullWorker implements Runnable {

		private final TransferState transfer;
		
		public PullWorker( TransferState transfer ) {
			this.transfer = transfer;
		}
		
		/* (non-Javadoc)
		 * @see java.lang.Runnable#run()
		 */
		@Override
		public void run() {
			try {
				pullHandler.handlePull(transfer.getPullRequest(), transfer.getPullOut());
			} catch ( Exception e ) {
				log.warn("UnhandledException in handlePull", e);
			}
			handlePullComplete( transfer.getCorrelationId() );
		}
	}
	
	public class TransferState {

		private final long startTimestamp;
		private final E pullRequest;
		private final F pushRequest;
		private final TransferOut pullOut;
		private final PushIn pushIn;
		
		public TransferState(long startTimestamp, E pullRequest, TransferOut pullOut) {
			this.startTimestamp = startTimestamp;
			this.pullRequest = pullRequest;
			this.pullOut = pullOut;
			this.pushRequest = null;
			this.pushIn = null;
		}

		public TransferState(long startTimestamp, F pushRequest, PushIn pushIn) {
			this.startTimestamp = startTimestamp;
			this.pullRequest = null;
			this.pullOut = null;
			this.pushRequest = pushRequest;
			this.pushIn = pushIn;
		}

		/**
		 * @return the startTimestamp
		 */
		public long getStartTimestamp() {
			return startTimestamp;
		}

		/**
		 * @return the request
		 */
		public E getPullRequest() {
			return pullRequest;
		}

		/**
		 * @return the request
		 */
		public F getPushRequest() {
			return pushRequest;
		}

		public int getCorrelationId() {
			if (pullOut != null) {
				return pullOut.getCorrelationId();
			} else if (pushIn != null) {
				return pushIn.getCorrelationId();
			}
			throw new IllegalStateException("missing transfer");
		}

		/**
		 * @return the pullOut
		 */
		public TransferOut getPullOut() {
			return pullOut;
		}

		/**
		 * @return the pushIn
		 */
		public PushIn getPushIn() {
			return pushIn;
		}

	}
}
