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
package org.apereo.cas.client.validation;

import org.apereo.cas.client.authentication.AttributePrincipal;
import org.apereo.cas.client.authentication.AttributePrincipalImpl;
import org.apereo.cas.client.proxy.Cas20ProxyRetriever;
import org.apereo.cas.client.proxy.ProxyGrantingTicketStorage;
import org.apereo.cas.client.proxy.ProxyRetriever;
import org.apereo.cas.client.util.CommonUtils;
import org.apereo.cas.client.util.XmlUtils;

import org.apache.commons.codec.binary.Base64;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.crypto.Cipher;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.StringReader;
import java.security.PrivateKey;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Implementation of the TicketValidator that will validate Service Tickets in compliance with the CAS 2.
 *
 * @author Scott Battaglia
 * @since 3.1
 */
public class Cas20ServiceTicketValidator extends AbstractCasProtocolUrlBasedTicketValidator {

    public static final String PGT_ATTRIBUTE = "proxyGrantingTicket";

    private static final String PGTIOU_PREFIX = "PGTIOU-";

    /** The CAS 2.0 protocol proxy callback url. */
    private String proxyCallbackUrl;

    /** The storage location of the proxy granting tickets. */
    private ProxyGrantingTicketStorage proxyGrantingTicketStorage;

    /** Implementation of the proxy retriever. */
    private ProxyRetriever proxyRetriever;

    /** Private key for decryption */
    private PrivateKey privateKey;

    /**
     * Constructs an instance of the CAS 2.0 Service Ticket Validator with the supplied
     * CAS server url prefix.
     *
     * @param casServerUrlPrefix the CAS Server URL prefix.
     */
    public Cas20ServiceTicketValidator(final String casServerUrlPrefix) {
        super(casServerUrlPrefix);
        this.proxyRetriever = new Cas20ProxyRetriever(casServerUrlPrefix, getEncoding(), getURLConnectionFactory());
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(final PrivateKey privateKey) {
        this.privateKey = privateKey;
    }

    /**
     * Adds the pgtUrl to the list of parameters to pass to the CAS server.
     *
     * @param urlParameters the Map containing the existing parameters to send to the server.
     */
    @Override
    protected void populateUrlAttributeMap(final Map<String, String> urlParameters) {
        urlParameters.put("pgtUrl", this.proxyCallbackUrl);
    }

    @Override
    protected String getUrlSuffix() {
        return "serviceValidate";
    }

    @Override
    protected Assertion parseResponseFromServer(final String response) throws TicketValidationException {
        final String error = parseAuthenticationFailureFromResponse(response);

        if (CommonUtils.isNotBlank(error)) {
            throw new TicketValidationException(error);
        }

        final String principal = parsePrincipalFromResponse(response);
        final String proxyGrantingTicket = retrieveProxyGrantingTicket(response);

        if (CommonUtils.isEmpty(principal)) {
            throw new TicketValidationException("No principal was found in the response from the CAS server.");
        }

        final Assertion assertion;
        final Map<String, Object> attributes = extractCustomAttributes(response);
        if (CommonUtils.isNotBlank(proxyGrantingTicket)) {
            attributes.remove(PGT_ATTRIBUTE);
            final AttributePrincipal attributePrincipal = new AttributePrincipalImpl(principal, attributes,
                proxyGrantingTicket, this.proxyRetriever);
            assertion = new AssertionImpl(attributePrincipal);
        } else {
            assertion = new AssertionImpl(new AttributePrincipalImpl(principal, attributes));
        }

        customParseResponse(response, assertion);

        return assertion;
    }

    protected String retrieveProxyGrantingTicket(final String response) {
        final List<String> values = XmlUtils.getTextForElements(response, PGT_ATTRIBUTE);
        for (final String value : values) {
            if (value != null) {
                if (value.startsWith(PGTIOU_PREFIX)) {
                    return retrieveProxyGrantingTicketFromStorage(value);
                } else {
                    return retrieveProxyGrantingTicketViaEncryption(value);
                }
            }
        }
        return null;
    }

    protected String retrieveProxyGrantingTicketFromStorage(final String pgtIou) {
        if (this.proxyGrantingTicketStorage != null) {
            return this.proxyGrantingTicketStorage.retrieve(pgtIou);
        }
        return null;
    }

    protected String retrieveProxyGrantingTicketViaEncryption(final String encryptedPgt) {
        if (this.privateKey != null) {
            try {
                final Cipher cipher = Cipher.getInstance(privateKey.getAlgorithm());
                final byte[] cred64 = new Base64().decode(encryptedPgt);
                cipher.init(Cipher.DECRYPT_MODE, privateKey);
                final byte[] cipherData = cipher.doFinal(cred64);
                final String pgt = new String(cipherData);
                logger.debug("Decrypted PGT: {}", pgt);
                return pgt;
            } catch (final Exception e) {
                logger.error("Unable to decrypt PGT", e);
            }
        }
        return null;
    }

    protected String parsePrincipalFromResponse(final String response) {
        return XmlUtils.getTextForElement(response, "user");
    }

    protected String parseAuthenticationFailureFromResponse(final String response) {
        return XmlUtils.getTextForElement(response, "authenticationFailure");
    }

    /**
     * Default attribute parsing of attributes that look like the following:
     * &lt;cas:attributes&gt;
     *  &lt;cas:attribute1&gt;value&lt;/cas:attribute1&gt;
     *  &lt;cas:attribute2&gt;value&lt;/cas:attribute2&gt;
     * &lt;/cas:attributes&gt;
     * <p>
     *
     * Attributes look like following also parsed correctly:
     * &lt;cas:attributes&gt;&lt;cas:attribute1&gt;value&lt;/cas:attribute1&gt;&lt;cas:attribute2&gt;value&lt;/cas:attribute2&gt;&lt;/cas:attributes&gt;
     * <p>
     *
     * This code is here merely for sample/demonstration purposes for those wishing to modify the CAS2 protocol.  You'll
     * probably want a more robust implementation or to use SAML 1.1
     *
     * @param xml the XML to parse.
     * @return the map of attributes.
     */
    protected Map<String, Object> extractCustomAttributes(final String xml) {
        final SAXParserFactory spf = SAXParserFactory.newInstance();
        spf.setNamespaceAware(true);
        spf.setValidating(false);
        try {
            final SAXParser saxParser = spf.newSAXParser();
            final XMLReader xmlReader = saxParser.getXMLReader();
            final CustomAttributeHandler handler = new CustomAttributeHandler();
            xmlReader.setContentHandler(handler);
            xmlReader.parse(new InputSource(new StringReader(xml)));
            return handler.getAttributes();
        } catch (final Exception e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    /**
     * Template method if additional custom parsing (such as Proxying) needs to be done.
     *
     * @param response the original response from the CAS server.
     * @param assertion the partially constructed assertion.
     * @throws TicketValidationException if there is a problem constructing the Assertion.
     */
    protected void customParseResponse(final String response, final Assertion assertion)
        throws TicketValidationException {
        // nothing to do
    }

    protected final String getProxyCallbackUrl() {
        return this.proxyCallbackUrl;
    }

    public final void setProxyCallbackUrl(final String proxyCallbackUrl) {
        this.proxyCallbackUrl = proxyCallbackUrl;
    }

    protected final ProxyGrantingTicketStorage getProxyGrantingTicketStorage() {
        return this.proxyGrantingTicketStorage;
    }

    public final void setProxyGrantingTicketStorage(final ProxyGrantingTicketStorage proxyGrantingTicketStorage) {
        this.proxyGrantingTicketStorage = proxyGrantingTicketStorage;
    }

    protected final ProxyRetriever getProxyRetriever() {
        return this.proxyRetriever;
    }

    public final void setProxyRetriever(final ProxyRetriever proxyRetriever) {
        this.proxyRetriever = proxyRetriever;
    }

    private class CustomAttributeHandler extends DefaultHandler {

        private Map<String, Object> attributes;

        private boolean foundAttributes;

        private String currentAttribute;

        private StringBuilder value;

        @Override
        public void startDocument() throws SAXException {
            this.attributes = new HashMap<String, Object>();
        }

        @Override
        public void startElement(final String namespaceURI, final String localName, final String qName,
                                 final Attributes attributes) throws SAXException {
            if ("attributes".equals(localName)) {
                this.foundAttributes = true;
            } else if (this.foundAttributes) {
                this.value = new StringBuilder();
                this.currentAttribute = localName;
            }
        }

        @Override
        public void endElement(final String namespaceURI, final String localName, final String qName)
            throws SAXException {
            if ("attributes".equals(localName)) {
                this.foundAttributes = false;
                this.currentAttribute = null;
            } else if (this.foundAttributes) {
                final Object o = this.attributes.get(this.currentAttribute);

                if (o == null) {
                    this.attributes.put(this.currentAttribute, this.value.toString());
                } else {
                    final List<Object> items;
                    if (o instanceof List) {
                        items = (List<Object>) o;
                    } else {
                        items = new LinkedList<Object>();
                        items.add(o);
                        this.attributes.put(this.currentAttribute, items);
                    }
                    items.add(this.value.toString());
                }
            }
        }

        @Override
        public void characters(final char[] chars, final int start, final int length) throws SAXException {
            if (this.currentAttribute != null) {
                value.append(chars, start, length);
            }
        }

        public Map<String, Object> getAttributes() {
            return this.attributes;
        }
    }
}
