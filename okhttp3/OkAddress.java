/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3;

import java.net.Proxy;
import java.net.ProxySelector;
import java.util.List;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.internal.OkUtil;

import static okhttp3.internal.OkUtil.equal;

/**
 * A specification for a connection to an origin server. For simple connections, this is the
 * server's hostname and port. If an explicit proxy is requested (or {@linkplain Proxy#NO_PROXY no
 * proxy} is explicitly requested), this also includes that proxy information. For secure
 * connections the address also includes the SSL socket factory, hostname verifier, and certificate
 * pinner.
 *
 * <p>HTTP requests that share the same {@code Address} may also share the same {@link OkConnection}.
 */
public final class OkAddress {
  final OkHttpUrl url;
  final OkDns dns;
  final SocketFactory socketFactory;
  final OkAuthenticator proxyAuthenticator;
  final List<OkProtocol> protocols;
  final List<OkConnectionSpec> connectionSpecs;
  final ProxySelector proxySelector;
  final Proxy proxy;
  final SSLSocketFactory sslSocketFactory;

  public OkAddress(String uriHost, int uriPort, OkDns dns, SocketFactory socketFactory,
                   SSLSocketFactory sslSocketFactory, OkAuthenticator proxyAuthenticator,
                   Proxy proxy, List<OkProtocol> protocols, List<OkConnectionSpec> connectionSpecs,
                   ProxySelector proxySelector) {
    this.url = new OkHttpUrl.Builder()
        .scheme(sslSocketFactory != null ? "https" : "http")
        .host(uriHost)
        .port(uriPort)
        .build();

    if (dns == null) throw new NullPointerException("dns == null");
    this.dns = dns;

    if (socketFactory == null) throw new NullPointerException("socketFactory == null");
    this.socketFactory = socketFactory;

    if (proxyAuthenticator == null) {
      throw new NullPointerException("proxyAuthenticator == null");
    }
    this.proxyAuthenticator = proxyAuthenticator;

    if (protocols == null) throw new NullPointerException("protocols == null");
    this.protocols = OkUtil.immutableList(protocols);

    if (connectionSpecs == null) throw new NullPointerException("connectionSpecs == null");
    this.connectionSpecs = OkUtil.immutableList(connectionSpecs);

    if (proxySelector == null) throw new NullPointerException("proxySelector == null");
    this.proxySelector = proxySelector;

    this.proxy = proxy;
    this.sslSocketFactory = sslSocketFactory;
  }

  /**
   * Returns a URL with the hostname and port of the origin server. The path, query, and fragment of
   * this URL are always empty, since they are not significant for planning a route.
   */
  public OkHttpUrl url() {
    return url;
  }

  /** Returns the service that will be used to resolve IP addresses for hostnames. */
  public OkDns dns() {
    return dns;
  }

  /** Returns the socket factory for new connections. */
  public SocketFactory socketFactory() {
    return socketFactory;
  }

  /** Returns the client's proxy authenticator. */
  public OkAuthenticator proxyAuthenticator() {
    return proxyAuthenticator;
  }

  /**
   * Returns the protocols the client supports. This method always returns a non-null list that
   * contains minimally {@link OkProtocol#HTTP_1_1}.
   */
  public List<OkProtocol> protocols() {
    return protocols;
  }

  public List<OkConnectionSpec> connectionSpecs() {
    return connectionSpecs;
  }

  /**
   * Returns this address's proxy selector. Only used if the proxy is null. If none of this
   * selector's proxies are reachable, a direct connection will be attempted.
   */
  public ProxySelector proxySelector() {
    return proxySelector;
  }

  /**
   * Returns this address's explicitly-specified HTTP proxy, or null to delegate to the {@linkplain
   * #proxySelector proxy selector}.
   */
  public Proxy proxy() {
    return proxy;
  }

  /** Returns the SSL socket factory, or null if this is not an HTTPS address. */
  public SSLSocketFactory sslSocketFactory() {
    return sslSocketFactory;
  }

  @Override public boolean equals(Object other) {
    return other instanceof OkAddress
        && url.equals(((OkAddress) other).url)
        && equalsNonHost((OkAddress) other);
  }

  @Override public int hashCode() {
    int result = 17;
    result = 31 * result + url.hashCode();
    result = 31 * result + dns.hashCode();
    result = 31 * result + proxyAuthenticator.hashCode();
    result = 31 * result + protocols.hashCode();
    result = 31 * result + connectionSpecs.hashCode();
    result = 31 * result + proxySelector.hashCode();
    result = 31 * result + (proxy != null ? proxy.hashCode() : 0);
    result = 31 * result + (sslSocketFactory != null ? sslSocketFactory.hashCode() : 0);
    return result;
  }

  boolean equalsNonHost(OkAddress that) {
    return this.dns.equals(that.dns)
        && this.proxyAuthenticator.equals(that.proxyAuthenticator)
        && this.protocols.equals(that.protocols)
        && this.connectionSpecs.equals(that.connectionSpecs)
        && this.proxySelector.equals(that.proxySelector)
        && equal(this.proxy, that.proxy)
        && equal(this.sslSocketFactory, that.sslSocketFactory)
        && this.url().port() == that.url().port();
  }

  @Override public String toString() {
    StringBuilder result = new StringBuilder()
        .append("Address{")
        .append(url.host()).append(":").append(url.port());

    if (proxy != null) {
      result.append(", proxy=").append(proxy);
    } else {
      result.append(", proxySelector=").append(proxySelector);
    }

    result.append("}");
    return result.toString();
  }
}