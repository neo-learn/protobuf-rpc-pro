/**
 *   Copyright 2010-2014 Peter Klauser
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
package com.googlecode.protobuf.pro.duplex.execute;

import com.google.protobuf.Message;
import com.google.protobuf.RpcCallback;
import com.googlecode.protobuf.pro.duplex.util.RenamingThreadFactoryProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;

/**
 * A ThreadPoolCallExecutor uses a pool of threads to handle the running of server side RPC calls.
 * This executor provides a cancellation facility where a running RPC callState can be interrupted before
 * it finishes, because a RPC cancel which comes logically after the RPC callState can be processed.
 * 
 * Thread separation between IO-Layer threads and threads calling the RPC methods is only needed if
 * the RPC method callState is going to make an RPC callState back to the SAME client who the callState is servicing.
 * If other threads than the IO-Layer threads are calling the server's RPC clients, then the 
 * {@link SameThreadExecutor} is sufficient.
 * 
 * It is necessary to use separate threads to handle server RPC calls than the threads which serve
 * the RPC client calls, in order to avoid deadlock on the single Netty Channel.
 * 
 * You can choose bounds for number of Threads to service the calls, and the BlockingQueue size and
 * implementation through the choice of constructors. {@link ThreadPoolExecutor}
 * discusses the choices here in detail.
 *
 * By default we use {@link ArrayBlockingQueue} to begin to throttle inbound calls
 * without building up a significant backlog ( introducing latency ). The ArrayBlockingQueue gives less
 * "Server Overload" issues due to thread scheduling situations ( thread not yet reading from queue when
 * the RPC inbound callState is entered ).
 *
 * Alternatively use {@link java.util.concurrent.SynchronousQueue} to avoid building up a backlog of queued
 * requests. If a RPC callState comes in where a Thread is not ready to handle, you will receive
 * a "Server Overload" error.
 *
 * Alternatively use {@link java.util.concurrent.LinkedBlockingQueue} without specifying any fixed capacity
 * to allow any number of pending requests queued indefinitely. This opens up the possibility of eventual
 * out of memory problems.
 *
 * @author Peter Klauser
 *
 */
public class AsyncThreadPoolCallExecutor extends ThreadPoolExecutor implements RpcServerCallExecutor, RejectedExecutionHandler {

	private static Logger log = LoggerFactory.getLogger(AsyncThreadPoolCallExecutor.class);

	Map<AsyncRpcCallRunner,AsyncRpcCallRunner> runningCalls = new ConcurrentHashMap<AsyncRpcCallRunner, AsyncRpcCallRunner>();

	public AsyncThreadPoolCallExecutor(int corePoolSize, int maximumPoolSize) {
		this(corePoolSize, maximumPoolSize, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(corePoolSize, false), new RenamingThreadFactoryProxy("rpc", Executors.defaultThreadFactory()) );
	}

	public AsyncThreadPoolCallExecutor(int corePoolSize, int maximumPoolSize, ThreadFactory threadFactory) {
		this(corePoolSize, maximumPoolSize, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(corePoolSize, false), threadFactory);
	}

	public AsyncThreadPoolCallExecutor(int corePoolSize, int maximumPoolSize,
                                       long keepAliveTime, TimeUnit unit,
                                       BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory) {
		super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
          threadFactory);
		setRejectedExecutionHandler(this);
	}
	
	/* (non-Javadoc)
	 * @see com.googlecode.protobuf.pro.duplex.execute.RpcServerCallExecutor#execute(com.googlecode.protobuf.pro.duplex.execute.PendingServerCallState)
	 */
	@Override
	public void execute(PendingServerCallState callState) {
		AsyncRpcCallRunner runner = new AsyncRpcCallRunner(callState, this);
		runningCalls.put(runner, runner);
		callState.setExecutor(runner);
		execute(runner);
	}

	/* (non-Javadoc)
	 * @see com.googlecode.protobuf.pro.duplex.execute.RpcServerCallExecutor#cancel(java.lang.Runnable)
	 */
	@Override
	public void cancel(Runnable executor) {
		AsyncRpcCallRunner runner = runningCalls.remove(executor);
		if ( runner != null ) {
			PendingServerCallState callState = runner.getCallState();
			
			// set the controller's cancel indicator
			callState.getController().startCancel();
			
			// interrupt the thread running the task, to hopefully unblock any
			// IO wait.
			Thread threadToCancel = runner.getRunningThread();
			if ( threadToCancel != null ) {
				threadToCancel.interrupt();
			}
		}
		// the running task, still should finish by returning "null" to the RPC done
		// callback.
	}

	/* (non-Javadoc)
	 * @see java.util.concurrent.ThreadPoolExecutor#beforeExecute(java.lang.Thread, java.lang.Runnable)
	 */
	@Override
	protected void beforeExecute(Thread t, Runnable r) {
		super.beforeExecute(t, r);
		
		AsyncRpcCallRunner runner = runningCalls.get(r);
		if ( runner != null ) {
			runner.setRunningThread(t);
		}
	}

	/* (non-Javadoc)
	 * @see java.util.concurrent.RejectedExecutionHandler#rejectedExecution(java.lang.Runnable, java.util.concurrent.ThreadPoolExecutor)
	 */
	@Override
	public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
		AsyncRpcCallRunner runner = runningCalls.remove(r);
		if ( runner != null ) {
			PendingServerCallState callState = runner.getCallState();
            callState.getController().setFailed("Server Overload");
            callState.getExecutorCallback().onFinish(callState.getController().getCorrelationId(), null);
			// if we didn't even start to run, we don't have to worry about on cancel notify because
			// the cancel notification callback could never have been set!
		}
	}


    private void onFinish(Runnable r) {
        AsyncRpcCallRunner runner = runningCalls.remove(r);
        if ( runner != null ) {
            ServerRpcController controller = runner.getCallState().getController();
            if ( controller.isCanceled() ) {
                // we don't care if there was a response created or error, the
                // client is not interested anymore. Just to the notification
                if ( controller.getAndSetCancelCallbackNotified() ) {
                    RpcCallback<Object> cancelCallback = controller.getCancelNotifyCallback();
                    if ( cancelCallback != null ) {
                        cancelCallback.run(null);
                    }
                }
            } else {
                if ( !runner.getAsyncRpcCallback().isDone() ) {
                    log.warn("RpcCallRunner did not finish RpcCall afterExecute. RpcCallRunner expected to complete calls, not offload them.");
                }
                runner.getCallState().getExecutorCallback().onFinish(runner.getCallState().getController().getCorrelationId(), runner.getAsyncRpcCallback().getMessage());
            }
        } else {
            if ( log.isDebugEnabled() ) {
                log.debug("Unable to find RpcCallRunner afterExecute - normal for a RpcCancel.");
            }
        }
    }

	private static class AsyncRpcCallRunner implements Runnable {

		private final PendingServerCallState callState;
		private final AsyncRpcCallback asyncRpcCallback;
        private final AsyncThreadPoolCallExecutor threadPoolCallExecutor;
        private final Runnable onFinish;
        private Thread runningThread = null;
		
		public AsyncRpcCallRunner(final PendingServerCallState callState, final AsyncThreadPoolCallExecutor threadPoolCallExecutor) {
			this.callState = callState;
            this.threadPoolCallExecutor = threadPoolCallExecutor;
            this.onFinish = new Runnable() {
                @Override
                public void run() {
                    threadPoolCallExecutor.onFinish(AsyncRpcCallRunner.this);
                }
            };
            asyncRpcCallback = new AsyncRpcCallback(onFinish);
		}
		
		@Override
		public void run() {
            try {
                if ( callState.getController().isCanceled() ){
                    // canceled before start - return immediately
                    asyncRpcCallback.run(null);
                    return;
                }
                // due to buffering in the RpcServerCallExecutor, we may already be timed-out
                // so we dont want to process the callState towards the RPC service.
                if( callState.isTimeoutExceeded() ) {
                    // set the controller's cancel indicator
                    callState.getController().startCancel();
                    asyncRpcCallback.run(null);
                    return;
                }
                if ( callState.getService() != null ) {
                    setRunningThread(null);//Current thread is not the running thread..
                    callState.getService().callMethod(callState.getMethodDesc(), callState.getController(), callState.getRequest(), asyncRpcCallback);
                } else {
                    Message responseMessage = callState.getBlockingService().callBlockingMethod(callState.getMethodDesc(), callState.getController(), callState.getRequest());
                    asyncRpcCallback.run(responseMessage);
                }
            } catch (Throwable cause){
                log.warn("AsyncRpcCallRunner threw ServiceException|msg=" + cause.getMessage(), cause);
                String errorMsg = cause.getClass().getName() + ": " + cause.getMessage();
                if ( cause.getCause() != null ) {
                    errorMsg += ". " + cause.getCause().getMessage();
                }
                callState.getController().setFailed(errorMsg);
                asyncRpcCallback.run(null);
            }
		}

		/**
		 * @param runningThread the runningThread to set
		 */
		public void setRunningThread(Thread runningThread) {
			this.runningThread = runningThread;
		}

		/**
		 * @return the callState
		 */
		public PendingServerCallState getCallState() {
			return callState;
		}

		/**
		 * @return the runningThread
		 */
		public Thread getRunningThread() {
			return runningThread;
		}

		/**
		 * @return the asyncRpcCallback
		 */
		public AsyncRpcCallback getAsyncRpcCallback() {
			return asyncRpcCallback;
		}
	}

}
