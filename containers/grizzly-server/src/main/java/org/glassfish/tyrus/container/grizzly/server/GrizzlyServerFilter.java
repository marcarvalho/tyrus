/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010-2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package org.glassfish.tyrus.container.grizzly.server;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.websocket.CloseReason;

import org.glassfish.tyrus.container.grizzly.client.GrizzlyWriter;
import org.glassfish.tyrus.core.RequestContext;
import org.glassfish.tyrus.core.Utils;
import org.glassfish.tyrus.core.WebSocket;
import org.glassfish.tyrus.core.WebSocketResponse;
import org.glassfish.tyrus.spi.ReadHandler;
import org.glassfish.tyrus.spi.ServerContainer;
import org.glassfish.tyrus.spi.UpgradeRequest;
import org.glassfish.tyrus.spi.UpgradeResponse;
import org.glassfish.tyrus.spi.WebSocketEngine;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.CloseListener;
import org.glassfish.grizzly.Closeable;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.ICloseType;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.attributes.AttributeHolder;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.Filter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.http.HttpClientFilter;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.HttpServerFilter;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.memory.ByteBufferArray;

/**
 * WebSocket {@link Filter} implementation, which supposed to be placed into a {@link FilterChain} right after HTTP
 * Filter: {@link HttpServerFilter}, {@link HttpClientFilter}; depending whether it's server or client side. The
 * <tt>WebSocketFilter</tt> handles websocket connection, handshake phases and, when receives a websocket frame -
 * redirects it to appropriate connection ({@link org.glassfish.tyrus.core.WebSocketApplication}, {@link org.glassfish.tyrus.core.WebSocket}) for processing.
 *
 * @author Alexey Stashok
 * @author Pavel Bucek (pavel.bucek at oracle.com)
 */
class GrizzlyServerFilter extends BaseFilter {

    private static final Logger logger = Grizzly.logger(GrizzlyServerFilter.class);

    private static final Attribute<org.glassfish.tyrus.spi.Connection> TYRUS_CONNECTION = Grizzly.DEFAULT_ATTRIBUTE_BUILDER
            .createAttribute(GrizzlyServerFilter.class.getName() + ".Connection");

    private final ServerContainer serverContainer;

    private final Deque<Task> taskDeque = new ConcurrentLinkedDeque<Task>();

    // ------------------------------------------------------------ Constructors

    /**
     * Constructs a new {@link GrizzlyServerFilter}.
     *
     * @param serverContainer TODO
     */
    public GrizzlyServerFilter(ServerContainer serverContainer) {
        this.serverContainer = serverContainer;
    }

    // ----------------------------------------------------- Methods from Filter

    /**
     * Method handles Grizzly {@link Connection} close phase. Check if the {@link Connection} is a {@link org.glassfish.tyrus.core.WebSocket}, if
     * yes - tries to close the websocket gracefully (sending close frame) and calls {@link
     * org.glassfish.tyrus.core.WebSocket#onClose(javax.websocket.CloseReason)}. If the Grizzly {@link Connection} is not websocket - passes processing to the next
     * filter in the chain.
     *
     * @param ctx {@link FilterChainContext}
     * @return {@link NextAction} instruction for {@link FilterChain}, how it should continue the execution
     * @throws IOException
     */
    @Override
    public NextAction handleClose(FilterChainContext ctx) throws IOException {

        final org.glassfish.tyrus.spi.Connection connection = getConnection(ctx);
        if (connection != null) {
            taskDeque.addLast(new CloseTask(connection, new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, null), ctx.getConnection()));
            processDeque(connection);
        }
        return ctx.getStopAction();
    }

    /**
     * Handle Grizzly {@link Connection} read phase. If the {@link Connection} has associated {@link WebSocket} object
     * (websocket connection), we check if websocket handshake has been completed for this connection, if not -
     * initiate/validate handshake. If handshake has been completed - parse websocket {@link org.glassfish.tyrus.core.DataFrame}s one by one and
     * pass processing to appropriate {@link WebSocket}: {@link org.glassfish.tyrus.core.WebSocketApplication} for server- and client- side
     * connections.
     *
     * @param ctx {@link FilterChainContext}
     * @return {@link NextAction} instruction for {@link FilterChain}, how it should continue the execution
     * @throws IOException TODO
     */
    @Override
    @SuppressWarnings("unchecked")
    public NextAction handleRead(FilterChainContext ctx) throws IOException {
        // Get the parsed HttpContent (we assume prev. filter was HTTP)
        final HttpContent message = ctx.getMessage();

        final org.glassfish.tyrus.spi.Connection tyrusConnection = getConnection(ctx);

        if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, "handleRead websocket: {0} content-size={1} headers=\n{2}",
                    new Object[]{tyrusConnection, message.getContent().remaining(), message.getHttpHeader()});
        }

        if (tyrusConnection == null) {
            // Get the HTTP header
            final HttpHeader header = message.getHttpHeader();

            // If websocket is null - it means either non-websocket Connection
            if (!UpgradeRequest.WEBSOCKET.equalsIgnoreCase(header.getUpgrade()) && message.getHttpHeader().isRequest()) {
                // if it's not a websocket connection - pass the processing to the next filter
                return ctx.getInvokeAction();
            }

            final String ATTR_NAME = "org.glassfish.tyrus.container.grizzly.WebSocketFilter.HANDSHAKE_PROCESSED";

            final AttributeHolder attributeHolder = ctx.getAttributes();
            if (attributeHolder != null) {
                final Object attribute = attributeHolder.getAttribute(ATTR_NAME);
                if (attribute != null) {
                    // handshake was already performed on this context.
                    return ctx.getInvokeAction();
                } else {
                    attributeHolder.setAttribute(ATTR_NAME, true);
                }
            }
            // Handle handshake
            return handleHandshake(ctx, message);
        }

        // tyrusConnection is not null
        // this is websocket with the completed handshake
        if (message.getContent().hasRemaining()) {
            // get the frame(s) content

            Buffer buffer = message.getContent();
            message.recycle();
            final ReadHandler readHandler = tyrusConnection.getReadHandler();
            if (!buffer.isComposite()) {
                taskDeque.addLast(new ProcessTask(buffer.toByteBuffer(), readHandler));
            } else {
                final ByteBufferArray byteBufferArray = buffer.toByteBufferArray();
                final ByteBuffer[] array = byteBufferArray.getArray();

                for (int i = 0; i < byteBufferArray.size(); i++) {
                    taskDeque.addLast(new ProcessTask(array[i], readHandler));
                }

                byteBufferArray.recycle();
            }

            processDeque(tyrusConnection);
        }
        return ctx.getStopAction();
    }

    private org.glassfish.tyrus.spi.Connection getConnection(FilterChainContext ctx) {
        return TYRUS_CONNECTION.get(ctx.getConnection());
    }

    // --------------------------------------------------------- Private Methods

    /**
     * Handle websocket handshake
     *
     * @param ctx     {@link FilterChainContext}
     * @param content HTTP message
     * @return {@link NextAction} instruction for {@link FilterChain}, how it should continue the execution
     * @throws IOException TODO
     */
    private NextAction handleHandshake(final FilterChainContext ctx, HttpContent content) throws IOException {
        final UpgradeRequest upgradeRequest = createWebSocketRequest(content);
        // TODO: final UpgradeResponse upgradeResponse = GrizzlyUpgradeResponse(HttpResponsePacket)
        final UpgradeResponse upgradeResponse = new WebSocketResponse();
        final WebSocketEngine.UpgradeInfo upgradeInfo = serverContainer.getWebSocketEngine().upgrade(upgradeRequest, upgradeResponse);

        switch (upgradeInfo.getStatus()) {
            case SUCCESS:
                final Connection grizzlyConnection = ctx.getConnection();
                write(ctx, upgradeRequest, upgradeResponse);

                final org.glassfish.tyrus.spi.Connection connection = upgradeInfo.createConnection(new GrizzlyWriter(ctx.getConnection()), new org.glassfish.tyrus.spi.Connection.CloseListener() {
                    @Override
                    public void close(CloseReason reason) {
                        grizzlyConnection.close();
                    }
                });

                TYRUS_CONNECTION.set(grizzlyConnection, connection);

                grizzlyConnection.addCloseListener(new CloseListener() {
                    @Override
                    public void onClosed(Closeable closeable, ICloseType type) throws IOException {
                        connection.close(new CloseReason(CloseReason.CloseCodes.GOING_AWAY, "Close detected on connection"));
                        // might not be necessary, connection is going to be recycled/freed anyway
                        TYRUS_CONNECTION.remove(grizzlyConnection);
                    }
                });

                return ctx.getStopAction();

            case HANDSHAKE_FAILED:
                write(ctx, upgradeRequest, upgradeResponse);
                content.recycle();
                return ctx.getStopAction();

            case NOT_APPLICABLE:
                return ctx.getInvokeAction();
        }

        return ctx.getStopAction();
    }

    private void write(FilterChainContext ctx, UpgradeRequest request, UpgradeResponse response) {
        final HttpResponsePacket responsePacket = ((HttpRequestPacket) ((HttpContent) ctx.getMessage()).getHttpHeader()).getResponse();
        responsePacket.setProtocol(Protocol.HTTP_1_1);
        responsePacket.setStatus(response.getStatus());

        // TODO
//        responsePacket.setReasonPhrase(response.getReasonPhrase());

        for (Map.Entry<String, List<String>> entry : response.getHeaders().entrySet()) {
            responsePacket.setHeader(entry.getKey(), Utils.getHeaderFromList(entry.getValue()));
        }

        ctx.write(HttpContent.builder(responsePacket).build());
    }

    private static UpgradeRequest createWebSocketRequest(final HttpContent requestContent) {

        final HttpRequestPacket requestPacket = (HttpRequestPacket) requestContent.getHttpHeader();

        final RequestContext requestContext = RequestContext.Builder.create()
                .requestURI(URI.create(requestPacket.getRequestURI()))
                .queryString(requestPacket.getQueryString())
                .secure(requestPacket.isSecure())
                .build();

        for (String name : requestPacket.getHeaders().names()) {
            final List<String> values = requestContext.getHeaders().get(name);
            if (values == null) {
                requestContext.getHeaders().put(name, Utils.parseHeaderValue(requestPacket.getHeader(name).trim()));
            } else {
                values.addAll(Utils.parseHeaderValue(requestPacket.getHeader(name).trim()));
            }
        }

        return requestContext;
    }

    protected void processDeque(final Object lock) {
        if (!taskDeque.isEmpty()) {
            do {
                final Task first = taskDeque.pollFirst();
                if (first == null) {
                    continue;
                }

                first.execute();
            } while (!taskDeque.isEmpty());
        }
    }

    private abstract class Task {
        public abstract void execute();
    }

    private class ProcessTask extends Task {
        private final ByteBuffer buffer;
        private final ReadHandler readHandler;

        private ProcessTask(ByteBuffer buffer, ReadHandler readHandler) {
            this.buffer = buffer;
            this.readHandler = readHandler;
        }

        @Override
        public void execute() {
            readHandler.handle(buffer);
        }
    }

    private class CloseTask extends Task {
        private final org.glassfish.tyrus.spi.Connection connection;
        private final CloseReason closeReason;
        private final Connection grizllyConnection;

        private CloseTask(org.glassfish.tyrus.spi.Connection connection, CloseReason closeReason, Connection grizzlyConnection) {
            this.connection = connection;
            this.closeReason = closeReason;
            this.grizllyConnection = grizzlyConnection;
        }

        @Override
        public void execute() {
            connection.close(closeReason);
            TYRUS_CONNECTION.remove(grizllyConnection);
        }
    }
}
