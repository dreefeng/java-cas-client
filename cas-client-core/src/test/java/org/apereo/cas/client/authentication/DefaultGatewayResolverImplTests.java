/**
 * Licensed to Apereo under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Apereo licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License.  You may obtain a
 * copy of the License at the following location:
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apereo.cas.client.authentication;

import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.Assert.*;

public class DefaultGatewayResolverImplTests {

    private DefaultGatewayResolverImpl resolver;

    @Before
    public void setUp() throws Exception {
        this.resolver = new DefaultGatewayResolverImpl();
    }

    @Test
    public void testReentrancy() throws Exception {
        final MockHttpServletRequest request1 = new MockHttpServletRequest();
        assertFalse(this.resolver.hasGatewayedAlready(request1, "foo"));
        assertFalse(this.resolver.hasGatewayedAlready(request1, "foo"));
        assertEquals("foo", this.resolver.storeGatewayInformation(request1, "foo"));
        assertEquals("foo", this.resolver.storeGatewayInformation(request1, "foo"));
        assertTrue(this.resolver.hasGatewayedAlready(request1, "foo"));
        assertTrue(this.resolver.hasGatewayedAlready(request1, "foo"));
    }

    @Test
    public void testSessionConcurrency() throws Exception {
        final MockHttpSession session = new MockHttpSession();
        final MockHttpServletRequest request1 = new MockHttpServletRequest();
        final MockHttpServletRequest request2 = new MockHttpServletRequest();
        request1.setSession(session);
        request2.setSession(session);

        assertFalse(this.resolver.hasGatewayedAlready(request1, "abc"));
        assertFalse(this.resolver.hasGatewayedAlready(request2, "def"));

        assertEquals("abc", this.resolver.storeGatewayInformation(request1, "abc"));

        assertTrue(this.resolver.hasGatewayedAlready(request2, "def"));
        assertTrue(this.resolver.hasGatewayedAlready(request1, "abc"));

        assertEquals("def", this.resolver.storeGatewayInformation(request2, "def"));

        assertTrue(this.resolver.hasGatewayedAlready(request1, "abc"));
        assertTrue(this.resolver.hasGatewayedAlready(request2, "def"));

    }
}
