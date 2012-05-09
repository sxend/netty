/*
 * Copyright 2011 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.socket.nio;

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.logging.InternalLogger;
import io.netty.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

public class SelectorEventLoop extends SingleThreadEventLoop {
    /**
     * Internal Netty logger.
     */
    protected static final InternalLogger logger = InternalLoggerFactory
            .getInstance(SelectorEventLoop.class);

    static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

    /**
     * The NIO {@link Selector}.
     */
    protected final Selector selector;

    /**
     * Boolean that controls determines if a blocked Selector.select should
     * break out of its selection process. In our case we use a timeone for
     * the select method and the select method will block for that time unless
     * waken up.
     */
    protected final AtomicBoolean wakenUp = new AtomicBoolean();

    private volatile int cancelledKeys; // should use AtomicInteger but we just need approximation

    public SelectorEventLoop() {
        this(Executors.defaultThreadFactory());
    }

    public SelectorEventLoop(ThreadFactory threadFactory) {
        this(threadFactory, SelectorProvider.provider());
    }

    public SelectorEventLoop(SelectorProvider selectorProvider) {
        this(Executors.defaultThreadFactory(), selectorProvider);
    }

    public SelectorEventLoop(ThreadFactory threadFactory, SelectorProvider selectorProvider) {
        super(threadFactory);
        if (selectorProvider == null) {
            throw new NullPointerException("selectorProvider");
        }
        selector = openSelector(selectorProvider);
    }

    private static Selector openSelector(SelectorProvider provider) {
        try {
            return provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }
    }

    @Override
    protected void run() {
        long lastConnectTimeoutCheckTimeNanos = System.nanoTime();

        Selector selector = this.selector;
        for (;;) {

            wakenUp.set(false);

            try {
                SelectorUtil.select(selector);

                // 'wakenUp.compareAndSet(false, true)' is always evaluated
                // before calling 'selector.wakeup()' to reduce the wake-up
                // overhead. (Selector.wakeup() is an expensive operation.)
                //
                // However, there is a race condition in this approach.
                // The race condition is triggered when 'wakenUp' is set to
                // true too early.
                //
                // 'wakenUp' is set to true too early if:
                // 1) Selector is waken up between 'wakenUp.set(false)' and
                //    'selector.select(...)'. (BAD)
                // 2) Selector is waken up between 'selector.select(...)' and
                //    'if (wakenUp.get()) { ... }'. (OK)
                //
                // In the first case, 'wakenUp' is set to true and the
                // following 'selector.select(...)' will wake up immediately.
                // Until 'wakenUp' is set to false again in the next round,
                // 'wakenUp.compareAndSet(false, true)' will fail, and therefore
                // any attempt to wake up the Selector will fail, too, causing
                // the following 'selector.select(...)' call to block
                // unnecessarily.
                //
                // To fix this problem, we wake up the selector again if wakenUp
                // is true immediately after selector.select(...).
                // It is inefficient in that it wakes up the selector for both
                // the first case (BAD - wake-up required) and the second case
                // (OK - no wake-up required).

                if (wakenUp.get()) {
                    selector.wakeup();
                }

                cancelledKeys = 0;
                processTaskQueue();
                processSelectedKeys(selector.selectedKeys());

                // Handle connection timeout every 10 milliseconds approximately.
                long currentTimeNanos = System.nanoTime();
                if (currentTimeNanos - lastConnectTimeoutCheckTimeNanos >= 10 * 1000000L) {
                    lastConnectTimeoutCheckTimeNanos = currentTimeNanos;
                    processConnectTimeout(selector.keys(), currentTimeNanos);
                }

                if (isShutdown()) {
                    // FIXME: Close all channels immediately and break the loop.
                    break;
                }
            } catch (Throwable t) {
                logger.warn(
                        "Unexpected exception in the selector loop.", t);

                // Prevent possible consecutive immediate failures that lead to
                // excessive CPU consumption.
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    // Ignore.
                }
            }
        }
    }

    @Override
    protected void cleanup() {
        try {
            selector.close();
        } catch (IOException e) {
            logger.warn(
                    "Failed to close a selector.", e);
        }
    }

    private void processTaskQueue() throws IOException {
        for (;;) {
            final Runnable task = pollTask();
            if (task == null) {
                break;
            }

            task.run();
            cleanUpCancelledKeys();
        }
    }

    private void processSelectedKeys(Set<SelectionKey> selectedKeys) throws IOException {
        for (Iterator<SelectionKey> i = selectedKeys.iterator(); i.hasNext();) {
            SelectionKey k = i.next();
            Channel ch = (Channel) k.attachment();
            boolean removeKey = true;
            try {

                int readyOps = k.readyOps();
                if ((readyOps & SelectionKey.OP_READ) != 0 || readyOps == 0) {
                    ch.unsafe().read();
                    if (!ch.isOpen()) {
                        // Connection already closed - no need to handle write.
                        continue;
                    }
                }
                if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                    ch.unsafe().flush(null);
                }

                if ((readyOps & SelectionKey.OP_ACCEPT) != 0) {
                    ch.unsafe().read();
                }
                if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                    ch.unsafe().finishConnect();
                }

            } catch (CancelledKeyException ignored) {
                ch.unsafe().close(null);
            } finally {
                if (removeKey) {
                    i.remove();
                }
            }

            if (cleanUpCancelledKeys()) {
                break; // break the loop to avoid ConcurrentModificationException
            }
        }
    }

    protected void processConnectTimeout(Set<SelectionKey> keys, long currentTimeNanos) {
        ConnectException cause = null;
        for (SelectionKey k: keys) {
            if (!k.isValid()) {
                // Comment the close call again as it gave us major problems with ClosedChannelExceptions.
                //
                // See:
                // * https://github.com/netty/netty/issues/142
                // * https://github.com/netty/netty/issues/138
                //
                //close(k);
                continue;
            }

            // Something is ready so skip it
            if (k.readyOps() != 0) {
                continue;
            }
            // check if the channel is in
            // FIXME: Implement connect timeout.
//            Channel ch = (Channel) k.attachment();
//            if (attachment instanceof NioClientSocketChannel) {
//                NioClientSocketChannel ch = (NioClientSocketChannel) attachment;
//                if (!ch.isConnected() && ch.connectDeadlineNanos > 0 && currentTimeNanos >= ch.connectDeadlineNanos) {
//
//                    if (cause == null) {
//                        cause = new ConnectException("connection timed out");
//                    }
//
//                    ch.connectFuture.setFailure(cause);
//                    fireExceptionCaught(ch, cause);
//                    ch.getWorker().close(ch, succeededFuture(ch));
//                }
//            }
        }
    }

    private boolean cleanUpCancelledKeys() throws IOException {
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            cancelledKeys = 0;
            selector.selectNow();
            return true;
        }
        return false;
    }

    @Override
    protected void wakeup(boolean inEventLoop) {
        if (wakenUp.compareAndSet(false, true)) {
            selector.wakeup();
        }
    }
}
