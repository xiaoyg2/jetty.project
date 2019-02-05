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

package org.eclipse.jetty.websocket.tests.server;

import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.Future;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.tests.CloseTrackingSocket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

/**
 * Tests various close scenarios
 */
public class ServerCloseTest
{
    private static final Logger LOG = Log.getLogger(ServerCloseTest.class);

    private static WebSocketClient client;
    private static Server server;
    private static ServerCloseCreator serverEndpointCreator;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        ServletHolder closeEndpoint = new ServletHolder(new WebSocketServlet()
        {
            @Override
            public void configure(WebSocketServletFactory factory)
            {
                WebSocketServerFactory serverFactory = (WebSocketServerFactory) factory;
                serverEndpointCreator = new ServerCloseCreator(serverFactory);
                factory.setCreator(serverEndpointCreator);
            }
        });
        context.addServlet(closeEndpoint, "/ws");

        HandlerList handlers = new HandlerList();
        handlers.addHandler(context);
        handlers.addHandler(new DefaultHandler());

        server.setHandler(handlers);

        server.start();
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @BeforeAll
    public static void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.setMaxIdleTimeout(SECONDS.toMillis(2));
        client.start();
    }

    @AfterAll
    public static void stopClient() throws Exception
    {
        client.stop();
    }

    private void close(Session session)
    {
        if (session != null)
        {
            session.close();
        }
    }

    /**
     * Test fast close (bug #403817)
     *
     * @throws Exception on test failure
     */
    @Test
    public void fastClose() throws Exception
    {
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("fastclose");
        CloseTrackingSocket clientEndpoint = new CloseTrackingSocket();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        Future<Session> futSession = client.connect(clientEndpoint, wsUri, request);

        Session session = null;
        try
        {
            session = futSession.get(5, SECONDS);

            // Verify that client got close
            clientEndpoint.assertReceivedCloseEvent(5000, is(StatusCode.NORMAL), containsString(""));

            // Verify that server socket got close event
            AbstractCloseSocket serverEndpoint = serverEndpointCreator.pollLastCreated();
            assertThat("Fast Close Latch", serverEndpoint.closeLatch.await(5, SECONDS), is(true));
            assertThat("Fast Close.statusCode", serverEndpoint.closeStatusCode, is(StatusCode.NORMAL));
        }
        finally
        {
            close(session);
        }
    }

    /**
     * Test fast fail (bug #410537)
     *
     * @throws Exception on test failure
     */
    @Test
    public void fastFail() throws Exception
    {
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("fastfail");
        CloseTrackingSocket clientEndpoint = new CloseTrackingSocket();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        Future<Session> futSession = client.connect(clientEndpoint, wsUri, request);

        Session session = null;
        try(StacklessLogging ignore = new StacklessLogging(FastFailSocket.class, WebSocketSession.class))
        {
            session = futSession.get(5, SECONDS);

            // Verify that client got close
            clientEndpoint.assertReceivedCloseEvent(5000, is(StatusCode.SERVER_ERROR), containsString("Intentional FastFail"));

            // Verify that server socket got close event
            AbstractCloseSocket serverEndpoint = serverEndpointCreator.pollLastCreated();
            serverEndpoint.assertReceivedCloseEvent(5000, is(StatusCode.SERVER_ERROR), containsString("Intentional FastFail"));

            // Validate errors (must be "java.lang.RuntimeException: Intentional Exception from onWebSocketConnect")
            assertThat("socket.onErrors", serverEndpoint.errors.size(), greaterThanOrEqualTo(1));
            Throwable cause = serverEndpoint.errors.poll(5, SECONDS);
            assertThat("Error type", cause, instanceOf(RuntimeException.class));
            // ... with optional ClosedChannelException
            cause = serverEndpoint.errors.peek();
            if (cause != null)
            {
                assertThat("Error type", cause, instanceOf(ClosedChannelException.class));
            }
        }
        finally
        {
            close(session);
        }
    }

    @Test
    public void dropConnection() throws Exception
    {
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("container");
        CloseTrackingSocket clientEndpoint = new CloseTrackingSocket();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        Future<Session> futSession = client.connect(clientEndpoint, wsUri, request);

        Session session = null;
        try(StacklessLogging ignore = new StacklessLogging(WebSocketSession.class))
        {
            session = futSession.get(5, SECONDS);

            // Cause a client endpoint failure
            clientEndpoint.getEndPoint().close();

            // Verify that client got close
            clientEndpoint.assertReceivedCloseEvent(5000, is(StatusCode.NO_CLOSE), containsString("Disconnected"));

            // Verify that server socket got close event
            AbstractCloseSocket serverEndpoint = serverEndpointCreator.pollLastCreated();
            serverEndpoint.assertReceivedCloseEvent(5000, is(StatusCode.NO_CLOSE), containsString("Read EOF"));
        } finally
        {
            close(session);
        }
    }


    /**
     * Test session open session cleanup (bug #474936)
     *
     * @throws Exception on test failure
     */
    @Test
    public void testOpenSessionCleanup() throws Exception
    {
        fastFail();
        fastClose();
        dropConnection();

        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("container");
        CloseTrackingSocket clientEndpoint = new CloseTrackingSocket();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        Future<Session> futSession = client.connect(clientEndpoint, wsUri, request);

        Session session = null;
        try(StacklessLogging ignore = new StacklessLogging(WebSocketSession.class))
        {
            session = futSession.get(5, SECONDS);

            session.getRemote().sendString("openSessions");

            String msg = clientEndpoint.messageQueue.poll(5, SECONDS);

            assertThat("Should only have 1 open session", msg, containsString("openSessions.size=1\n"));

            // Verify that client got close
            clientEndpoint.assertReceivedCloseEvent(5000, is(StatusCode.SHUTDOWN), containsString(""));

            // Verify that server socket got close event
            AbstractCloseSocket serverEndpoint = serverEndpointCreator.pollLastCreated();
            assertThat("Server Open Sessions Latch", serverEndpoint.closeLatch.await(5, SECONDS), is(true));
            assertThat("Server Open Sessions.statusCode", serverEndpoint.closeStatusCode, is(StatusCode.SHUTDOWN));
            assertThat("Server Open Sessions.errors", serverEndpoint.errors.size(), is(0));
        }
        finally
        {
            close(session);
        }
    }
}
