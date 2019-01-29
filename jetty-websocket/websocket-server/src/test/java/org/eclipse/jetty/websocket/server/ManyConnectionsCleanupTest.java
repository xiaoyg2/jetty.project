//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.server;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.message.TrackingSocket;
import org.eclipse.jetty.websocket.common.test.BlockheadClient;
import org.eclipse.jetty.websocket.common.test.BlockheadClientRequest;
import org.eclipse.jetty.websocket.common.test.BlockheadConnection;
import org.eclipse.jetty.websocket.common.test.Timeouts;
import org.eclipse.jetty.websocket.server.helper.RFCSocket;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests various close scenarios that should result in Open Session cleanup
 */
public class ManyConnectionsCleanupTest
{
    static class AbstractCloseSocket extends WebSocketAdapter
    {
        public CountDownLatch closeLatch = new CountDownLatch(1);
        public String closeReason = null;
        public int closeStatusCode = -1;
        public LinkedBlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            LOG.debug("{}.onWebSocketClose({}, {}) - {}", this.getClass().getSimpleName(), statusCode, reason, getSession());
            this.closeStatusCode = statusCode;
            this.closeReason = reason;
            closeLatch.countDown();
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            errors.offer(cause);
        }
    }

    @SuppressWarnings("serial")
    public static class CloseServlet extends WebSocketServlet implements WebSocketCreator
    {
        private WebSocketServerFactory serverFactory;
        private AtomicInteger calls = new AtomicInteger(0);

        @Override
        public void configure(WebSocketServletFactory factory)
        {
            factory.setCreator(this);
            if (factory instanceof WebSocketServerFactory)
            {
                this.serverFactory = (WebSocketServerFactory)factory;
            }
        }

        @Override
        public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
        {
            if (req.hasSubProtocol("fastclose"))
            {
                closeSocket = new FastCloseSocket(calls);
                return closeSocket;
            }

            if (req.hasSubProtocol("fastfail"))
            {
                closeSocket = new FastFailSocket(calls);
                return closeSocket;
            }

            if (req.hasSubProtocol("container"))
            {
                closeSocket = new ContainerSocket(serverFactory,calls);
                return closeSocket;
            }
            return new RFCSocket();
        }
    }

    /**
     * On Message, return container information
     */
    public static class ContainerSocket extends AbstractCloseSocket
    {
        private static final Logger LOG = Log.getLogger(ManyConnectionsCleanupTest.ContainerSocket.class);
        private final WebSocketServerFactory container;
        private final AtomicInteger calls;

        public ContainerSocket(WebSocketServerFactory container, AtomicInteger calls)
        {
            this.container = container;
            this.calls = calls;
        }

        @Override
        public void onWebSocketText(String message)
        {
            LOG.debug("onWebSocketText({})",message);
            try
            {
                calls.incrementAndGet();
                if (message.equalsIgnoreCase("openSessions"))
                {
                    Collection<WebSocketSession> sessions = container.getOpenSessions();

                    StringBuilder ret = new StringBuilder();
                    ret.append("openSessions.size=").append(sessions.size()).append('\n');
                    int idx = 0;
                    for (WebSocketSession sess : sessions)
                    {
                        ret.append('[').append(idx++).append("] ").append(sess.toString()).append('\n');
                    }
                    getRemote().sendString(ret.toString());
                    getSession().close(StatusCode.NORMAL, "Close from server");
                }
                else if (message.equalsIgnoreCase("calls"))
                {
                    getRemote().sendString(String.format("calls=%,d", calls.get()));
                }
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    /**
     * On Connect, close socket
     */
    public static class FastCloseSocket extends AbstractCloseSocket
    {
        private static final Logger LOG = Log.getLogger(ManyConnectionsCleanupTest.FastCloseSocket.class);
        private final AtomicInteger calls;

        public FastCloseSocket(AtomicInteger calls)
        {
            this.calls = calls;
        }

        @Override
        public void onWebSocketConnect(Session sess)
        {
            LOG.debug("onWebSocketConnect({})",sess);
            calls.incrementAndGet();
            sess.close(StatusCode.NORMAL,"FastCloseServer");
        }
    }

    /**
     * On Connect, throw unhandled exception
     */
    public static class FastFailSocket extends AbstractCloseSocket
    {
        private static final Logger LOG = Log.getLogger(ManyConnectionsCleanupTest.FastFailSocket.class);
        private final AtomicInteger calls;

        public FastFailSocket(AtomicInteger calls)
        {
            this.calls = calls;
        }

        @Override
        public void onWebSocketConnect(Session sess)
        {
            LOG.debug("onWebSocketConnect({})",sess);
            calls.incrementAndGet();
            // Test failure due to unhandled exception
            // this should trigger a fast-fail closure during open/connect
            throw new RuntimeException("Intentional FastFail");
        }
    }

    private static final Logger LOG = Log.getLogger(ManyConnectionsCleanupTest.class);

    private static BlockheadClient client;
    private static SimpleServletServer server;
    private static AbstractCloseSocket closeSocket;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new CloseServlet());
        server.start();
    }

    @AfterAll
    public static void stopServer()
    {
        server.stop();
    }

    @BeforeAll
    public static void startClient() throws Exception
    {
        client = new BlockheadClient();
        client.setIdleTimeout(TimeUnit.SECONDS.toMillis(2));
        client.start();
    }

    @AfterAll
    public static void stopClient() throws Exception
    {
        client.stop();
    }

    /**
     * Test session open session cleanup (bug #474936)
     * 
     * @throws Exception
     *             on test failure
     */
    @Test
    public void testOpenSessionCleanup() throws Exception
    {
        int iterationCount = 20;

        // TODO: consider a SilentLogging alternative class
        try(StacklessLogging ignore = new StacklessLogging(FastFailSocket.class, WebSocketSession.class))
        {
            for (int requests = 0; requests < iterationCount; requests++)
            {
                fastFail();
                fastClose();
                dropConnection();
            }
        }

        WebSocketClient client = new WebSocketClient();
        client.setMaxIdleTimeout(1000);
        client.start();

        try
        {
            TrackingSocket clientSocket = new TrackingSocket();
            ClientUpgradeRequest headers = new ClientUpgradeRequest();
            headers.setSubProtocols("container");
            Future<Session> fut = client.connect(clientSocket, server.getServerUri(), headers);
            Session session = fut.get(10, TimeUnit.SECONDS);

            RemoteEndpoint remote = session.getRemote();
            remote.sendString("calls");
            remote.sendString("openSessions");

            String msg;

            msg = clientSocket.messageQueue.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Calls received on server", msg, containsString("calls=" + ((iterationCount * 2) + 1)));

            msg = clientSocket.messageQueue.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Open session on server", msg, containsString("openSessions.size=1\n"));

            clientSocket.assertCloseCode(StatusCode.NORMAL);
        }
        finally
        {
            client.stop();
        }
    }

    private void fastClose() throws Exception
    {
        BlockheadClientRequest request = client.newWsRequest(server.getServerUri());
        request.header(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, "fastclose");
        request.idleTimeout(1, TimeUnit.SECONDS);

        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT);
             StacklessLogging ignore = new StacklessLogging(WebSocketSession.class))
        {
            LinkedBlockingQueue<WebSocketFrame> frames = clientConn.getFrameQueue();
            frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);

            CloseInfo close = new CloseInfo(StatusCode.NORMAL,"Normal");
            assertThat("Close Status Code",close.getStatusCode(),is(StatusCode.NORMAL));

            // Notify server of close handshake
            clientConn.write(close.asFrame()); // respond with close

            // ensure server socket got close event
            assertThat("Fast Close Latch",closeSocket.closeLatch.await(1,TimeUnit.SECONDS),is(true));
            assertThat("Fast Close.statusCode",closeSocket.closeStatusCode,is(StatusCode.NORMAL));
        }
    }

    private void fastFail() throws Exception
    {
        BlockheadClientRequest request = client.newWsRequest(server.getServerUri());
        request.header(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, "fastfail");
        request.idleTimeout(1, TimeUnit.SECONDS);

        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT);
             StacklessLogging ignore = new StacklessLogging(WebSocketSession.class))
        {
            CloseInfo close = new CloseInfo(StatusCode.NORMAL,"Normal");
            clientConn.write(close.asFrame()); // respond with close

            // ensure server socket got close event
            assertThat("Fast Fail Latch",closeSocket.closeLatch.await(1,TimeUnit.SECONDS),is(true));
            assertThat("Fast Fail.statusCode",closeSocket.closeStatusCode,is(StatusCode.SERVER_ERROR));

            // Validate errors (must be "java.lang.RuntimeException: Intentional Exception from onWebSocketConnect")
            assertThat("socket.onErrors",closeSocket.errors.size(),greaterThanOrEqualTo(1));
            Throwable cause = closeSocket.errors.poll(5, TimeUnit.SECONDS);
            assertThat("Error type",cause,instanceOf(RuntimeException.class));
            // ... with optional ClosedChannelException
            cause = closeSocket.errors.peek();
            if(cause != null)
                assertThat("Error type",cause,instanceOf(ClosedChannelException.class));
        }
    }
    
    private void dropConnection() throws Exception
    {
        BlockheadClientRequest request = client.newWsRequest(server.getServerUri());
        request.header(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, "container");
        request.idleTimeout(1, TimeUnit.SECONDS);

        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT);
             StacklessLogging ignore = new StacklessLogging(WebSocketSession.class))
        {
            clientConn.abort();
        }
    }
}
