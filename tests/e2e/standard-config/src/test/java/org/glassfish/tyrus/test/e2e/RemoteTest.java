/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011 - 2013 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.tyrus.test.e2e;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.DeploymentException;
import javax.websocket.EndpointConfig;
import javax.websocket.Session;

import org.glassfish.tyrus.test.tools.TestContainer;
import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.server.Server;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the WebSocket remote for basic types
 *
 * @author Stepan Kopriva (stepan.kopriva at oracle.com)
 */
public class RemoteTest extends TestContainer {

    private CountDownLatch messageLatch;

    private String receivedMessage;


    @Test
    public void testBooleanFAIL() throws DeploymentException {
        testPojo(org.glassfish.tyrus.test.e2e.bean.stin.BooleanBean.class, "/standardInputTypes/boolean", "String", "FAIL");
    }

    @Test
    public void testBooleanPASS() throws DeploymentException {
        testPojo(org.glassfish.tyrus.test.e2e.bean.stin.BooleanBean.class, "/standardInputTypes/boolean", "true", "PASS");
    }

    @Test
    public void testCharFAIL() throws DeploymentException {
        testPojo(org.glassfish.tyrus.test.e2e.bean.stin.CharBean.class, "/standardInputTypes/char", "fasd", "FAIL");
    }

    @Test
    public void testCharPASS() throws DeploymentException {
        testPojo(org.glassfish.tyrus.test.e2e.bean.stin.CharBean.class, "/standardInputTypes/char", "c", "PASS");
    }

    @Test
    public void testDouble() throws DeploymentException {
        testPojo(org.glassfish.tyrus.test.e2e.bean.stin.DoubleBean.class, "/standardInputTypes/double", "42.0", "PASS");
    }

    @Test
    public void testFloat() throws DeploymentException {
        testPojo(org.glassfish.tyrus.test.e2e.bean.stin.FloatBean.class, "/standardInputTypes/float", "42.0", "PASS");
    }

    @Test
    public void testInt() throws DeploymentException {
        testPojo(org.glassfish.tyrus.test.e2e.bean.stin.IntBean.class, "/standardInputTypes/int", "42", "PASS");
    }

    @Test
    public void testLong() throws DeploymentException {
        testPojo(org.glassfish.tyrus.test.e2e.bean.stin.LongBean.class, "/standardInputTypes/long", "42", "PASS");
    }

    @Test
    public void testShort() throws DeploymentException {
        testPojo(org.glassfish.tyrus.test.e2e.bean.stin.ShortBean.class, "/standardInputTypes/short", "42", "PASS");
    }

    public void testPojo(Class<?> bean, String segmentPath, final String message, String response) throws DeploymentException {
        final ClientEndpointConfig cec = ClientEndpointConfig.Builder.create().build();
        Server server = startServer(bean);

        try {
            messageLatch = new CountDownLatch(1);

            ClientManager client = ClientManager.createClient();
            client.connectToServer(new TestEndpointAdapter() {

                @Override
                public EndpointConfig getEndpointConfig() {
                    return null;
                }

                @Override
                public void onOpen(Session session) {
                    try {
                        session.addMessageHandler(new TestTextMessageHandler(this));
                        session.getBasicRemote().sendText(message);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onMessage(String message) {
                    receivedMessage = message;
                    messageLatch.countDown();
                }
            }, cec, getURI(segmentPath));
            messageLatch.await(5, TimeUnit.SECONDS);
            Assert.assertEquals(response, receivedMessage);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            stopServer(server);
        }
    }
}
