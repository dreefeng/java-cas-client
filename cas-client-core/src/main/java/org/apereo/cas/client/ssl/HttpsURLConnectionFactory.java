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
package org.apereo.cas.client.ssl;

import org.apereo.cas.client.util.CommonUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.HttpURLConnection;
import java.net.URLConnection;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Properties;

/**
 * An implementation of the {@link HttpURLConnectionFactory} whose responsible to configure
 * the underlying <i>https</i> connection, if needed, with a given hostname and SSL socket factory based on the
 * configuration provided. 
 *
 * @author Misagh Moayyed
 * @since 3.3
 * @see #setHostnameVerifier(HostnameVerifier)
 * @see #setSSLConfiguration(Properties)
 */
public final class HttpsURLConnectionFactory implements HttpURLConnectionFactory {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpsURLConnectionFactory.class);

    /**
     * Hostname verifier used when making an SSL request to the CAS server.
     * Defaults to {@link HttpsURLConnection#getDefaultHostnameVerifier()}
     */
    private HostnameVerifier hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier();

    /**
     * Properties file that can contains key/trust info for Client Side Certificates
     */
    private Properties sslConfiguration = new Properties();

    public HttpsURLConnectionFactory() {
    }

    public HttpsURLConnectionFactory(final HostnameVerifier verifier, final Properties config) {
        setHostnameVerifier(verifier);
        setSSLConfiguration(config);
    }

    public final void setSSLConfiguration(final Properties config) {
        this.sslConfiguration = config;
    }

    /**
     * Set the host name verifier for the https connection received.
     *
     * @see AnyHostnameVerifier
     * @see RegexHostnameVerifier
     * @see WhitelistHostnameVerifier
     */
    public final void setHostnameVerifier(final HostnameVerifier verifier) {
        this.hostnameVerifier = verifier;
    }

    @Override
    public HttpURLConnection buildHttpURLConnection(final URLConnection url) {
        return this.configureHttpsConnectionIfNeeded(url);
    }

    @Override
    public int hashCode() {
        int result = hostnameVerifier.hashCode();
        result = 31 * result + sslConfiguration.hashCode();
        return result;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final HttpsURLConnectionFactory that = (HttpsURLConnectionFactory) o;

        if (!hostnameVerifier.equals(that.hostnameVerifier)) {
            return false;
        }
        if (!sslConfiguration.equals(that.sslConfiguration)) {
            return false;
        }

        return true;
    }

    /**
     * Configures the connection with specific settings for secure http connections
     * If the connection instance is not a {@link HttpsURLConnection},
     * no additional changes will be made and the connection itself is simply returned.
     *
     * @param conn the http connection
     */
    private HttpURLConnection configureHttpsConnectionIfNeeded(final URLConnection conn) {
        if (conn instanceof HttpsURLConnection) {
            final HttpsURLConnection httpsConnection = (HttpsURLConnection) conn;
            final SSLSocketFactory socketFactory = this.createSSLSocketFactory();
            if (socketFactory != null) {
                httpsConnection.setSSLSocketFactory(socketFactory);
            }

            if (isIgnoreSslFailures()) {
                httpsConnection.setHostnameVerifier(new AnyHostnameVerifier());
            } else if (this.hostnameVerifier != null) {
                httpsConnection.setHostnameVerifier(this.hostnameVerifier);
            }
        }
        return (HttpURLConnection) conn;
    }

    private boolean isIgnoreSslFailures() {
        return this.sslConfiguration.getProperty("ignoreSslFailures", "false").equalsIgnoreCase("true");
    }

    /**
     * Creates a {@link SSLSocketFactory} based on the configuration specified
     * <p>
     * Sample properties file:
     * <pre>
     * protocol=TLS
     * keyStoreType=JKS
     * keyStorePath=/var/secure/location/.keystore
     * keyStorePass=changeit
     * certificatePassword=aGoodPass
     * </pre>
     * @return the {@link SSLSocketFactory}
     */
    private SSLSocketFactory createSSLSocketFactory() {
        InputStream keyStoreIS = null;

        try {
            final SSLContext sslContext = SSLContext.getInstance(this.sslConfiguration.getProperty("protocol", "SSL"));
            if (isIgnoreSslFailures()) {
                final TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
                    public void checkClientTrusted(final X509Certificate[] certs, final String authType) {
                    }

                    public void checkServerTrusted(final X509Certificate[] certs, final String authType) {
                    }

                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }
                }};
                sslContext.init(null, trustAllCerts, new SecureRandom());
                return sslContext.getSocketFactory();
            }

            if (this.sslConfiguration.getProperty("keyStoreType") != null) {
                final KeyStore keyStore = KeyStore.getInstance(this.sslConfiguration.getProperty("keyStoreType"));
                if (this.sslConfiguration.getProperty("keyStorePath") != null) {
                    keyStoreIS = new FileInputStream(this.sslConfiguration.getProperty("keyStorePath"));
                    if (this.sslConfiguration.getProperty("keyStorePass") != null) {
                        keyStore.load(keyStoreIS, this.sslConfiguration.getProperty("keyStorePass").toCharArray());
                        LOGGER.debug("Keystore has {} keys", keyStore.size());
                        final KeyManagerFactory keyManager = KeyManagerFactory.getInstance(this.sslConfiguration
                            .getProperty("keyManagerType", "SunX509"));
                        keyManager.init(keyStore, this.sslConfiguration.getProperty("certificatePassword").toCharArray());
                        sslContext.init(keyManager.getKeyManagers(), null, null);
                        return sslContext.getSocketFactory();
                    }
                }
            }

        } catch (final Exception e) {
            LOGGER.error(e.getMessage(), e);
        } finally {
            CommonUtils.closeQuietly(keyStoreIS);
        }
        return null;
    }

    private void writeObject(final ObjectOutputStream out) throws IOException {
        if (this.hostnameVerifier == HttpsURLConnection.getDefaultHostnameVerifier()) {
            out.writeObject(null);
        } else {
            out.writeObject(this.hostnameVerifier);
        }

        out.writeObject(this.sslConfiguration);

    }

    private void readObject(final ObjectInputStream in) throws IOException, ClassNotFoundException {
        final Object internalHostNameVerifier = in.readObject();
        if (internalHostNameVerifier == null) {
            this.hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
        } else {
            this.hostnameVerifier = (HostnameVerifier) internalHostNameVerifier;
        }

        this.sslConfiguration = (Properties) in.readObject();
    }
}
