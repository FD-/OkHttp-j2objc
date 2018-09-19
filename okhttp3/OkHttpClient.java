/*
 * Copyright (C) 2012 Square, Inc.
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
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.internal.OkInternal;
import okhttp3.internal.OkUtil;
import okhttp3.internal.cache.OkInternalCache;
import okhttp3.internal.connection.OkRealConnection;
import okhttp3.internal.connection.OkRouteDatabase;
import okhttp3.internal.connection.OkStreamAllocation;
import okhttp3.internal.platform.OkPlatform;
import okhttp3.internal.ws.OkRealWebSocket;
import okio.OkioSink;
import okio.OkioSource;

import static okhttp3.internal.OkUtil.assertionError;
import static okhttp3.internal.OkUtil.checkDuration;

/**
 * Factory for {@linkplain OkCall calls}, which can be used to send HTTP requests and read their
 * responses.
 *
 * <h3>OkHttpClients should be shared</h3>
 *
 * <p>OkHttp performs best when you create a single {@code OkHttpClient} instance and reuse it for
 * all of your HTTP calls. This is because each client holds its own connection pool and thread
 * pools. Reusing connections and threads reduces latency and saves memory. Conversely, creating a
 * client for each request wastes resources on idle pools.
 *
 * <p>Use {@code new OkHttpClient()} to create a shared instance with the default settings:
 * <pre>   {@code
 *
 *   // The singleton HTTP client.
 *   public final OkHttpClient client = new OkHttpClient();
 * }</pre>
 *
 * <p>Or use {@code new OkHttpClient.Builder()} to create a shared instance with custom settings:
 * <pre>   {@code
 *
 *   // The singleton HTTP client.
 *   public final OkHttpClient client = new OkHttpClient.Builder()
 *       .addInterceptor(new HttpLoggingInterceptor())
 *       .cache(new Cache(cacheDir, cacheSize))
 *       .build();
 * }</pre>
 *
 * <h3>Customize your client with newBuilder()</h3>
 *
 * <p>You can customize a shared OkHttpClient instance with {@link #newBuilder()}. This builds a
 * client that shares the same connection pool, thread pools, and configuration. Use the builder
 * methods to configure the derived client for a specific purpose.
 *
 * <p>This example shows a call with a short 500 millisecond timeout: <pre>   {@code
 *
 *   OkHttpClient eagerClient = client.newBuilder()
 *       .readTimeout(500, TimeUnit.MILLISECONDS)
 *       .build();
 *   Response response = eagerClient.newCall(request).execute();
 * }</pre>
 *
 * <h3>Shutdown isn't necessary</h3>
 *
 * <p>The threads and connections that are held will be released automatically if they remain idle.
 * But if you are writing a application that needs to aggressively release unused resources you may
 * do so.
 *
 * <p>Shutdown the dispatcher's executor service with {@link ExecutorService#shutdown shutdown()}.
 * This will also cause future calls to the client to be rejected. <pre>   {@code
 *
 *     client.dispatcher().executorService().shutdown();
 * }</pre>
 *
 * <p>Clear the connection pool with {@link OkConnectionPool#evictAll() evictAll()}. Note that the
 * connection pool's daemon thread may not exit immediately. <pre>   {@code
 *
 *     client.connectionPool().evictAll();
 * }</pre>
 *
 * <p>If your client has a cache, call {@link OkCache#close close()}. Note that it is an error to
 * create calls against a cache that is closed, and doing so will cause the call to crash.
 * <pre>   {@code
 *
 *     client.cache().close();
 * }</pre>
 *
 * <p>OkHttp also uses daemon threads for HTTP/2 connections. These will exit automatically if they
 * remain idle.
 */
public class OkHttpClient implements Cloneable, OkCall.Factory, OkWebSocket.Factory {
  static final List<OkProtocol> DEFAULT_PROTOCOLS = OkUtil.immutableList(
      OkProtocol.HTTP_2, OkProtocol.HTTP_1_1);

  static final List<OkConnectionSpec> DEFAULT_CONNECTION_SPECS = OkUtil.immutableList(
      OkConnectionSpec.TLS, OkConnectionSpec.CLEARTEXT);

  static {
    OkInternal.instance = new OkInternal() {
      @Override public void addLenient(OkHeaders.Builder builder, String line) {
        builder.addLenient(line);
      }

      @Override public void addLenient(OkHeaders.Builder builder, String name, String value) {
        builder.addLenient(name, value);
      }

      @Override public void setCache(Builder builder, OkInternalCache internalCache) {
        builder.setInternalCache(internalCache);
      }

      @Override public boolean connectionBecameIdle(
              OkConnectionPool pool, OkRealConnection connection) {
        return pool.connectionBecameIdle(connection);
      }

      @Override public OkRealConnection get(OkConnectionPool pool, OkAddress address,
                                            OkStreamAllocation streamAllocation, OkRoute route) {
        return pool.get(address, streamAllocation, route);
      }

      @Override public boolean equalsNonHost(OkAddress a, OkAddress b) {
        return a.equalsNonHost(b);
      }

      @Override public Socket deduplicate(
              OkConnectionPool pool, OkAddress address, OkStreamAllocation streamAllocation) {
        return pool.deduplicate(address, streamAllocation);
      }

      @Override public void put(OkConnectionPool pool, OkRealConnection connection) {
        pool.put(connection);
      }

      @Override public OkRouteDatabase routeDatabase(OkConnectionPool connectionPool) {
        return connectionPool.routeDatabase;
      }

      @Override public int code(OkResponse.Builder responseBuilder) {
        return responseBuilder.code;
      }

      @Override
      public void apply(OkConnectionSpec tlsConfiguration, SSLSocket sslSocket, boolean isFallback) {
      }

      @Override public boolean isInvalidHttpUrlHost(IllegalArgumentException e) {
        return e.getMessage().startsWith(OkHttpUrl.Builder.INVALID_HOST);
      }

      @Override public OkStreamAllocation streamAllocation(OkCall call) {
        return ((OkRealCall) call).streamAllocation();
      }

      @Override public OkCall newWebSocketCall(OkHttpClient client, OkRequest originalRequest) {
        return OkRealCall.newRealCall(client, originalRequest, true);
      }
    };
  }

  final OkDispatcher dispatcher;
  final Proxy proxy;
  final List<OkProtocol> protocols;
  final List<OkConnectionSpec> connectionSpecs;
  final List<OkInterceptor> interceptors;
  final List<OkInterceptor> networkInterceptors;
  final OkEventListener.Factory eventListenerFactory;
  final ProxySelector proxySelector;
  final OkCookieJar cookieJar;
  final
  OkCache cache;
  final
  OkInternalCache internalCache;
  final SocketFactory socketFactory;
  final SSLSocketFactory sslSocketFactory;
  final OkAuthenticator proxyAuthenticator;
  final OkAuthenticator authenticator;
  final OkConnectionPool connectionPool;
  final OkDns dns;
  final boolean followSslRedirects;
  final boolean followRedirects;
  final boolean retryOnConnectionFailure;
  final int connectTimeout;
  final int readTimeout;
  final int writeTimeout;
  final int pingInterval;

  public OkHttpClient() {
    this(new Builder());
  }

  OkHttpClient(Builder builder) {
    this.dispatcher = builder.dispatcher;
    this.proxy = builder.proxy;
    this.protocols = builder.protocols;
    this.connectionSpecs = builder.connectionSpecs;
    this.interceptors = OkUtil.immutableList(builder.interceptors);
    this.networkInterceptors = OkUtil.immutableList(builder.networkInterceptors);
    this.eventListenerFactory = builder.eventListenerFactory;
    this.proxySelector = builder.proxySelector;
    this.cookieJar = builder.cookieJar;
    this.cache = builder.cache;
    this.internalCache = builder.internalCache;
    this.socketFactory = builder.socketFactory;

    boolean isTLS = false;
    for (OkConnectionSpec spec : connectionSpecs) {
      isTLS = isTLS || spec.isTls();
    }

    this.sslSocketFactory = builder.sslSocketFactory;

    this.proxyAuthenticator = builder.proxyAuthenticator;
    this.authenticator = builder.authenticator;
    this.connectionPool = builder.connectionPool;
    this.dns = builder.dns;
    this.followSslRedirects = builder.followSslRedirects;
    this.followRedirects = builder.followRedirects;
    this.retryOnConnectionFailure = builder.retryOnConnectionFailure;
    this.connectTimeout = builder.connectTimeout;
    this.readTimeout = builder.readTimeout;
    this.writeTimeout = builder.writeTimeout;
    this.pingInterval = builder.pingInterval;

    if (interceptors.contains(null)) {
      throw new IllegalStateException("Null interceptor: " + interceptors);
    }
    if (networkInterceptors.contains(null)) {
      throw new IllegalStateException("Null network interceptor: " + networkInterceptors);
    }
  }

  private static SSLSocketFactory newSslSocketFactory(X509TrustManager trustManager) {
    try {
      SSLContext sslContext = OkPlatform.get().getSSLContext();
      sslContext.init(null, new TrustManager[] { trustManager }, null);
      return sslContext.getSocketFactory();
    } catch (GeneralSecurityException e) {
      throw assertionError("No System TLS", e); // The system has no TLS. Just give up.
    }
  }

  /** Default connect timeout (in milliseconds). */
  public int connectTimeoutMillis() {
    return connectTimeout;
  }

  /** Default read timeout (in milliseconds). */
  public int readTimeoutMillis() {
    return readTimeout;
  }

  /** Default write timeout (in milliseconds). */
  public int writeTimeoutMillis() {
    return writeTimeout;
  }

  /** Web socket ping interval (in milliseconds). */
  public int pingIntervalMillis() {
    return pingInterval;
  }

  public Proxy proxy() {
    return proxy;
  }

  public ProxySelector proxySelector() {
    return proxySelector;
  }

  public OkCookieJar cookieJar() {
    return cookieJar;
  }

  public
  OkCache cache() {
    return cache;
  }

  OkInternalCache internalCache() {
    return cache != null ? cache.internalCache : internalCache;
  }

  public OkDns dns() {
    return dns;
  }

  public SocketFactory socketFactory() {
    return socketFactory;
  }

  public SSLSocketFactory sslSocketFactory() {
    return sslSocketFactory;
  }

  public OkAuthenticator authenticator() {
    return authenticator;
  }

  public OkAuthenticator proxyAuthenticator() {
    return proxyAuthenticator;
  }

  public OkConnectionPool connectionPool() {
    return connectionPool;
  }

  public boolean followSslRedirects() {
    return followSslRedirects;
  }

  public boolean followRedirects() {
    return followRedirects;
  }

  public boolean retryOnConnectionFailure() {
    return retryOnConnectionFailure;
  }

  public OkDispatcher dispatcher() {
    return dispatcher;
  }

  public List<OkProtocol> protocols() {
    return protocols;
  }

  public List<OkConnectionSpec> connectionSpecs() {
    return connectionSpecs;
  }

  /**
   * Returns an immutable list of interceptors that observe the full span of each call: from before
   * the connection is established (if any) until after the response source is selected (either the
   * origin server, cache, or both).
   */
  public List<OkInterceptor> interceptors() {
    return interceptors;
  }

  /**
   * Returns an immutable list of interceptors that observe a single network request and response.
   * These interceptors must call {@link OkInterceptor.Chain#proceed} exactly once: it is an error for
   * a network interceptor to short-circuit or repeat a network request.
   */
  public List<OkInterceptor> networkInterceptors() {
    return networkInterceptors;
  }

  public OkEventListener.Factory eventListenerFactory() {
    return eventListenerFactory;
  }

  /**
   * Prepares the {@code request} to be executed at some point in the future.
   */
  @Override public OkCall newCall(OkRequest request) {
    return OkRealCall.newRealCall(this, request, false /* for web socket */);
  }

  /**
   * Uses {@code request} to connect a new web socket.
   */
  @Override public OkWebSocket newWebSocket(OkRequest request, OkWebSocketListener listener) {
    OkRealWebSocket webSocket = new OkRealWebSocket(request, listener, new Random(), pingInterval);
    webSocket.connect(this);
    return webSocket;
  }

  public Builder newBuilder() {
    return new Builder(this);
  }

  public static final class Builder {
    OkDispatcher dispatcher;
    Proxy proxy;
    List<OkProtocol> protocols;
    List<OkConnectionSpec> connectionSpecs;
    final List<OkInterceptor> interceptors = new ArrayList<>();
    final List<OkInterceptor> networkInterceptors = new ArrayList<>();
    OkEventListener.Factory eventListenerFactory;
    ProxySelector proxySelector;
    OkCookieJar cookieJar;

    OkCache cache;

    OkInternalCache internalCache;
    SocketFactory socketFactory;
    SSLSocketFactory sslSocketFactory;
    OkAuthenticator proxyAuthenticator;
    OkAuthenticator authenticator;
    OkConnectionPool connectionPool;
    OkDns dns;
    boolean followSslRedirects;
    boolean followRedirects;
    boolean retryOnConnectionFailure;
    int connectTimeout;
    int readTimeout;
    int writeTimeout;
    int pingInterval;

    public Builder() {
      dispatcher = new OkDispatcher();
      protocols = DEFAULT_PROTOCOLS;
      connectionSpecs = DEFAULT_CONNECTION_SPECS;
      eventListenerFactory = OkEventListener.factory(OkEventListener.NONE);
      proxySelector = ProxySelector.getDefault();
      cookieJar = OkCookieJar.NO_COOKIES;
      socketFactory = SocketFactory.getDefault();
      proxyAuthenticator = OkAuthenticator.NONE;
      authenticator = OkAuthenticator.NONE;
      connectionPool = new OkConnectionPool();
      dns = OkDns.SYSTEM;
      followSslRedirects = true;
      followRedirects = true;
      retryOnConnectionFailure = true;
      connectTimeout = 10_000;
      readTimeout = 10_000;
      writeTimeout = 10_000;
      pingInterval = 0;
    }

    Builder(OkHttpClient okHttpClient) {
      this.dispatcher = okHttpClient.dispatcher;
      this.proxy = okHttpClient.proxy;
      this.protocols = okHttpClient.protocols;
      this.connectionSpecs = okHttpClient.connectionSpecs;
      this.interceptors.addAll(okHttpClient.interceptors);
      this.networkInterceptors.addAll(okHttpClient.networkInterceptors);
      this.eventListenerFactory = okHttpClient.eventListenerFactory;
      this.proxySelector = okHttpClient.proxySelector;
      this.cookieJar = okHttpClient.cookieJar;
      this.internalCache = okHttpClient.internalCache;
      this.cache = okHttpClient.cache;
      this.socketFactory = okHttpClient.socketFactory;
      this.sslSocketFactory = okHttpClient.sslSocketFactory;
      this.proxyAuthenticator = okHttpClient.proxyAuthenticator;
      this.authenticator = okHttpClient.authenticator;
      this.connectionPool = okHttpClient.connectionPool;
      this.dns = okHttpClient.dns;
      this.followSslRedirects = okHttpClient.followSslRedirects;
      this.followRedirects = okHttpClient.followRedirects;
      this.retryOnConnectionFailure = okHttpClient.retryOnConnectionFailure;
      this.connectTimeout = okHttpClient.connectTimeout;
      this.readTimeout = okHttpClient.readTimeout;
      this.writeTimeout = okHttpClient.writeTimeout;
      this.pingInterval = okHttpClient.pingInterval;
    }

    /**
     * Sets the default connect timeout for new connections. A value of 0 means no timeout,
     * otherwise values must be between 1 and {@link Integer#MAX_VALUE} when converted to
     * milliseconds.
     *
     * <p>The connectTimeout is applied when connecting a TCP socket to the target host.
     * The default value is 10 seconds.
     */
    public Builder connectTimeout(long timeout, TimeUnit unit) {
      connectTimeout = checkDuration("timeout", timeout, unit);
      return this;
    }

    /**
     * Sets the default read timeout for new connections. A value of 0 means no timeout, otherwise
     * values must be between 1 and {@link Integer#MAX_VALUE} when converted to milliseconds.
     *
     * <p>The read timeout is applied to both the TCP socket and for individual read IO operations
     * including on {@link OkioSource} of the {@link OkResponse}. The default value is 10 seconds.
     *
     * @see Socket#setSoTimeout(int)
     * @see OkioSource#timeout()
     */
    public Builder readTimeout(long timeout, TimeUnit unit) {
      readTimeout = checkDuration("timeout", timeout, unit);
      return this;
    }

    /**
     * Sets the default write timeout for new connections. A value of 0 means no timeout, otherwise
     * values must be between 1 and {@link Integer#MAX_VALUE} when converted to milliseconds.
     *
     * <p>The write timeout is applied for individual write IO operations.
     * The default value is 10 seconds.
     *
     * @see OkioSink#timeout()
     */
    public Builder writeTimeout(long timeout, TimeUnit unit) {
      writeTimeout = checkDuration("timeout", timeout, unit);
      return this;
    }

    /**
     * Sets the interval between HTTP/2 and web socket pings initiated by this client. Use this to
     * automatically send ping frames until either the connection fails or it is closed. This keeps
     * the connection alive and may detect connectivity failures.
     *
     * <p>If the server does not respond to each ping with a pong within {@code interval}, this
     * client will assume that connectivity has been lost. When this happens on a web socket the
     * connection is canceled and its listener is {@linkplain OkWebSocketListener#onFailure notified
     * of the failure}. When it happens on an HTTP/2 connection the connection is closed and any
     * calls it is carrying {@linkplain java.io.IOException will fail with an IOException}.
     *
     * <p>The default value of 0 disables client-initiated pings.
     */
    public Builder pingInterval(long interval, TimeUnit unit) {
      pingInterval = checkDuration("interval", interval, unit);
      return this;
    }

    /**
     * Sets the HTTP proxy that will be used by connections created by this client. This takes
     * precedence over {@link #proxySelector}, which is only honored when this proxy is null (which
     * it is by default). To disable proxy use completely, call {@code proxy(Proxy.NO_PROXY)}.
     */
    public Builder proxy(Proxy proxy) {
      this.proxy = proxy;
      return this;
    }

    /**
     * Sets the proxy selection policy to be used if no {@link #proxy proxy} is specified
     * explicitly. The proxy selector may return multiple proxies; in that case they will be tried
     * in sequence until a successful connection is established.
     *
     * <p>If unset, the {@link ProxySelector#getDefault() system-wide default} proxy selector will
     * be used.
     */
    public Builder proxySelector(ProxySelector proxySelector) {
      if (proxySelector == null) throw new NullPointerException("proxySelector == null");
      this.proxySelector = proxySelector;
      return this;
    }

    /**
     * Sets the handler that can accept cookies from incoming HTTP responses and provides cookies to
     * outgoing HTTP requests.
     *
     * <p>If unset, {@linkplain OkCookieJar#NO_COOKIES no cookies} will be accepted nor provided.
     */
    public Builder cookieJar(OkCookieJar cookieJar) {
      if (cookieJar == null) throw new NullPointerException("cookieJar == null");
      this.cookieJar = cookieJar;
      return this;
    }

    /** Sets the response cache to be used to read and write cached responses. */
    void setInternalCache(OkInternalCache internalCache) {
      this.internalCache = internalCache;
      this.cache = null;
    }

    /** Sets the response cache to be used to read and write cached responses. */
    public Builder cache(OkCache cache) {
      this.cache = cache;
      this.internalCache = null;
      return this;
    }

    /**
     * Sets the DNS service used to lookup IP addresses for hostnames.
     *
     * <p>If unset, the {@link OkDns#SYSTEM system-wide default} DNS will be used.
     */
    public Builder dns(OkDns dns) {
      if (dns == null) throw new NullPointerException("dns == null");
      this.dns = dns;
      return this;
    }

    /**
     * Sets the socket factory used to create connections. OkHttp only uses the parameterless {@link
     * SocketFactory#createSocket() createSocket()} method to create unconnected sockets. Overriding
     * this method, e. g., allows the socket to be bound to a specific local address.
     *
     * <p>If unset, the {@link SocketFactory#getDefault() system-wide default} socket factory will
     * be used.
     */
    public Builder socketFactory(SocketFactory socketFactory) {
      if (socketFactory == null) throw new NullPointerException("socketFactory == null");
      this.socketFactory = socketFactory;
      return this;
    }

    /**
     * Sets the socket factory used to secure HTTPS connections. If unset, the system default will
     * be used.
     *
     */
    public Builder sslSocketFactory(SSLSocketFactory sslSocketFactory) {
      if (sslSocketFactory == null) throw new NullPointerException("sslSocketFactory == null");
      this.sslSocketFactory = sslSocketFactory;
      return this;
    }


    /**
     * Sets the authenticator used to respond to challenges from origin servers. Use {@link
     * #proxyAuthenticator} to set the authenticator for proxy servers.
     *
     * <p>If unset, the {@linkplain OkAuthenticator#NONE no authentication will be attempted}.
     */
    public Builder authenticator(OkAuthenticator authenticator) {
      if (authenticator == null) throw new NullPointerException("authenticator == null");
      this.authenticator = authenticator;
      return this;
    }

    /**
     * Sets the authenticator used to respond to challenges from proxy servers. Use {@link
     * #authenticator} to set the authenticator for origin servers.
     *
     * <p>If unset, the {@linkplain OkAuthenticator#NONE no authentication will be attempted}.
     */
    public Builder proxyAuthenticator(OkAuthenticator proxyAuthenticator) {
      if (proxyAuthenticator == null) throw new NullPointerException("proxyAuthenticator == null");
      this.proxyAuthenticator = proxyAuthenticator;
      return this;
    }

    /**
     * Sets the connection pool used to recycle HTTP and HTTPS connections.
     *
     * <p>If unset, a new connection pool will be used.
     */
    public Builder connectionPool(OkConnectionPool connectionPool) {
      if (connectionPool == null) throw new NullPointerException("connectionPool == null");
      this.connectionPool = connectionPool;
      return this;
    }

    /**
     * Configure this client to follow redirects from HTTPS to HTTP and from HTTP to HTTPS.
     *
     * <p>If unset, protocol redirects will be followed. This is different than the built-in {@code
     * HttpURLConnection}'s default.
     */
    public Builder followSslRedirects(boolean followProtocolRedirects) {
      this.followSslRedirects = followProtocolRedirects;
      return this;
    }

    /** Configure this client to follow redirects. If unset, redirects will be followed. */
    public Builder followRedirects(boolean followRedirects) {
      this.followRedirects = followRedirects;
      return this;
    }

    /**
     * Configure this client to retry or not when a connectivity problem is encountered. By default,
     * this client silently recovers from the following problems:
     *
     * <ul>
     *   <li><strong>Unreachable IP addresses.</strong> If the URL's host has multiple IP addresses,
     *       failure to reach any individual IP address doesn't fail the overall request. This can
     *       increase availability of multi-homed services.
     *   <li><strong>Stale pooled connections.</strong> The {@link OkConnectionPool} reuses sockets
     *       to decrease request latency, but these connections will occasionally time out.
     *   <li><strong>Unreachable proxy servers.</strong> A {@link ProxySelector} can be used to
     *       attempt multiple proxy servers in sequence, eventually falling back to a direct
     *       connection.
     * </ul>
     *
     * Set this to false to avoid retrying requests when doing so is destructive. In this case the
     * calling application should do its own recovery of connectivity failures.
     */
    public Builder retryOnConnectionFailure(boolean retryOnConnectionFailure) {
      this.retryOnConnectionFailure = retryOnConnectionFailure;
      return this;
    }

    /**
     * Sets the dispatcher used to set policy and execute asynchronous requests. Must not be null.
     */
    public Builder dispatcher(OkDispatcher dispatcher) {
      if (dispatcher == null) throw new IllegalArgumentException("dispatcher == null");
      this.dispatcher = dispatcher;
      return this;
    }

    /**
     * Configure the protocols used by this client to communicate with remote servers. By default
     * this client will prefer the most efficient transport available, falling back to more
     * ubiquitous protocols. Applications should only call this method to avoid specific
     * compatibility problems, such as web servers that behave incorrectly when HTTP/2 is enabled.
     *
     * <p>The following protocols are currently supported:
     *
     * <ul>
     *     <li><a href="http://www.w3.org/Protocols/rfc2616/rfc2616.html">http/1.1</a>
     *     <li><a href="https://tools.ietf.org/html/rfc7540">h2</a>
     *     <li><a href="https://tools.ietf.org/html/rfc7540#section-3.4">h2 with prior knowledge
     *         (cleartext only)</a>
     * </ul>
     *
     * <p><strong>This is an evolving set.</strong> Future releases include support for transitional
     * protocols. The http/1.1 transport will never be dropped.
     *
     * <p>If multiple protocols are specified, <a
     * href="http://tools.ietf.org/html/draft-ietf-tls-applayerprotoneg">ALPN</a> will be used to
     * negotiate a transport. Protocol negotiation is only attempted for HTTPS URLs.
     *
     * <p>{@link OkProtocol#HTTP_1_0} is not supported in this set. Requests are initiated with {@code
     * HTTP/1.1}. If the server responds with {@code HTTP/1.0}, that will be exposed by {@link
     * OkResponse#protocol()}.
     *
     * @param protocols the protocols to use, in order of preference. If the list contains {@link
     *     OkProtocol#H2_PRIOR_KNOWLEDGE} then that must be the only protocol and HTTPS URLs will not
     *     be supported. Otherwise the list must contain {@link OkProtocol#HTTP_1_1}. The list must
     *     not contain null or {@link OkProtocol#HTTP_1_0}.
     */
    public Builder protocols(List<OkProtocol> protocols) {
      // Create a private copy of the list.
      protocols = new ArrayList<>(protocols);

      // Validate that the list has everything we require and nothing we forbid.
      if (!protocols.contains(OkProtocol.H2_PRIOR_KNOWLEDGE)
          && !protocols.contains(OkProtocol.HTTP_1_1)) {
        throw new IllegalArgumentException(
            "protocols must contain h2_prior_knowledge or http/1.1: " + protocols);
      }
      if (protocols.contains(OkProtocol.H2_PRIOR_KNOWLEDGE) && protocols.size() > 1) {
        throw new IllegalArgumentException(
            "protocols containing h2_prior_knowledge cannot use other protocols: " + protocols);
      }
      if (protocols.contains(OkProtocol.HTTP_1_0)) {
        throw new IllegalArgumentException("protocols must not contain http/1.0: " + protocols);
      }
      if (protocols.contains(null)) {
        throw new IllegalArgumentException("protocols must not contain null");
      }

      // Remove protocols that we no longer support.
      protocols.remove(OkProtocol.SPDY_3);

      // Assign as an unmodifiable list. This is effectively immutable.
      this.protocols = Collections.unmodifiableList(protocols);
      return this;
    }

    public Builder connectionSpecs(List<OkConnectionSpec> connectionSpecs) {
      this.connectionSpecs = OkUtil.immutableList(connectionSpecs);
      return this;
    }

    /**
     * Returns a modifiable list of interceptors that observe the full span of each call: from
     * before the connection is established (if any) until after the response source is selected
     * (either the origin server, cache, or both).
     */
    public List<OkInterceptor> interceptors() {
      return interceptors;
    }

    public Builder addInterceptor(OkInterceptor interceptor) {
      if (interceptor == null) throw new IllegalArgumentException("interceptor == null");
      interceptors.add(interceptor);
      return this;
    }

    /**
     * Returns a modifiable list of interceptors that observe a single network request and response.
     * These interceptors must call {@link OkInterceptor.Chain#proceed} exactly once: it is an error
     * for a network interceptor to short-circuit or repeat a network request.
     */
    public List<OkInterceptor> networkInterceptors() {
      return networkInterceptors;
    }

    public Builder addNetworkInterceptor(OkInterceptor interceptor) {
      if (interceptor == null) throw new IllegalArgumentException("interceptor == null");
      networkInterceptors.add(interceptor);
      return this;
    }

    /**
     * Configure a single client scoped listener that will receive all analytic events
     * for this client.
     *
     * @see OkEventListener for semantics and restrictions on listener implementations.
     */
    public Builder eventListener(OkEventListener eventListener) {
      if (eventListener == null) throw new NullPointerException("eventListener == null");
      this.eventListenerFactory = OkEventListener.factory(eventListener);
      return this;
    }

    /**
     * Configure a factory to provide per-call scoped listeners that will receive analytic events
     * for this client.
     *
     * @see OkEventListener for semantics and restrictions on listener implementations.
     */
    public Builder eventListenerFactory(OkEventListener.Factory eventListenerFactory) {
      if (eventListenerFactory == null) {
        throw new NullPointerException("eventListenerFactory == null");
      }
      this.eventListenerFactory = eventListenerFactory;
      return this;
    }

    public OkHttpClient build() {
      return new OkHttpClient(this);
    }
  }
}
