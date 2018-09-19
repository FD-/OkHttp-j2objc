/*
 * Copyright (C) 2012 Square, Inc.
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
package okhttp3.internal.platform;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;
import okhttp3.OkProtocol;

/**
 * Access to platform-specific features.
 *
 * <h3>Server name indication (SNI)</h3>
 *
 * <p>Supported on Android 2.3+.
 *
 * Supported on OpenJDK 7+
 *
 * <h3>Session Tickets</h3>
 *
 * <p>Supported on Android 2.3+.
 *
 * <h3>Android Traffic Stats (Socket Tagging)</h3>
 *
 * <p>Supported on Android 4.0+.
 *
 * <h3>ALPN (Application Layer Protocol Negotiation)</h3>
 *
 * <p>Supported on Android 5.0+. The APIs were present in Android 4.4, but that implementation was
 * unstable.
 *
 * Supported on OpenJDK 7 and 8 (via the JettyALPN-boot library).
 *
 * Supported on OpenJDK 9 via SSLParameters and SSLSocket features.
 *
 * <h3>Trust Manager Extraction</h3>
 *
 * <p>Supported on Android 2.3+ and OpenJDK 7+. There are no public APIs to recover the trust
 * manager that was used to create an {@link SSLSocketFactory}.
 *
 * <h3>Android Cleartext Permit Detection</h3>
 *
 * <p>Supported on Android 6.0+ via {@code NetworkSecurityPolicy}.
 */
public class OkPlatform {
  private static final OkPlatform PLATFORM = findPlatform();
  public static final int INFO = 4;
  public static final int WARN = 5;
  private static final Logger logger = Logger.getLogger(OkHttpClient.class.getName());

  public static OkPlatform get() {
    return PLATFORM;
  }

  /** Prefix used on custom headers. */
  public String getPrefix() {
    return "OkHttp";
  }

  protected X509TrustManager trustManager(SSLSocketFactory sslSocketFactory) {
    // Attempt to get the trust manager from an OpenJDK socket factory. We attempt this on all
    // platforms in order to support Robolectric, which mixes classes from both Android and the
    // Oracle JDK. Note that we don't support HTTP/2 or other nice features on Robolectric.
    try {
      Class<?> sslContextClass = Class.forName("sun.security.ssl.SSLContextImpl");
      Object context = readFieldOrNull(sslSocketFactory, sslContextClass, "context");
      if (context == null) return null;
      return readFieldOrNull(context, X509TrustManager.class, "trustManager");
    } catch (ClassNotFoundException e) {
      return null;
    }
  }

  /**
   * Configure TLS extensions on {@code sslSocket} for {@code route}.
   *
   * @param hostname non-null for client-side handshakes; null for server-side handshakes.
   */
  public void configureTlsExtensions(SSLSocket sslSocket, String hostname,
      List<OkProtocol> protocols) {
  }

  /**
   * Called after the TLS handshake to release resources allocated by {@link
   * #configureTlsExtensions}.
   */
  public void afterHandshake(SSLSocket sslSocket) {
  }

  /** Returns the negotiated protocol, or null if no protocol was negotiated. */
  public String getSelectedProtocol(SSLSocket socket) {
    return null;
  }

  public void connectSocket(Socket socket, InetSocketAddress address, int connectTimeout)
      throws IOException {
    socket.connect(address, connectTimeout);
  }

  public void log(int level, String message, Throwable t) {
    Level logLevel = level == WARN ? Level.WARNING : Level.INFO;
    logger.log(logLevel, message, t);
  }

  public boolean isCleartextTrafficPermitted(String hostname) {
    return true;
  }

  /**
   * Returns an object that holds a stack trace created at the moment this method is executed. This
   * should be used specifically for {@link java.io.Closeable} objects and in conjunction with
   * {@link #logCloseableLeak(String, Object)}.
   */
  public Object getStackTraceForCloseable(String closer) {
    if (logger.isLoggable(Level.FINE)) {
      return new Throwable(closer); // These are expensive to allocate.
    }
    return null;
  }

  public void logCloseableLeak(String message, Object stackTrace) {
    if (stackTrace == null) {
      message += " To see where this was allocated, set the OkHttpClient logger level to FINE: "
          + "Logger.getLogger(OkHttpClient.class.getName()).setLevel(Level.FINE);";
    }
    log(WARN, message, (Throwable) stackTrace);
  }

  public static List<String> alpnProtocolNames(List<OkProtocol> protocols) {
    List<String> names = new ArrayList<>(protocols.size());
    for (int i = 0, size = protocols.size(); i < size; i++) {
      OkProtocol protocol = protocols.get(i);
      if (protocol == OkProtocol.HTTP_1_0) continue; // No HTTP/1.0 for ALPN.
      names.add(protocol.toString());
    }
    return names;
  }

  public static boolean isConscryptPreferred() {
    // mainly to allow tests to run cleanly
    if ("conscrypt".equals(System.getProperty("okhttp.platform"))) {
      return true;
    }

    // check if Provider manually installed
    String preferredProvider = Security.getProviders()[0].getName();
    return "Conscrypt".equals(preferredProvider);
  }

  /** Attempt to match the host runtime to a capable Platform implementation. */
  private static OkPlatform findPlatform() {
    return new OkiOSPlatform();
  }

  static <T> T readFieldOrNull(Object instance, Class<T> fieldType, String fieldName) {
    for (Class<?> c = instance.getClass(); c != Object.class; c = c.getSuperclass()) {
      try {
        Field field = c.getDeclaredField(fieldName);
        field.setAccessible(true);
        Object value = field.get(instance);
        if (value == null || !fieldType.isInstance(value)) return null;
        return fieldType.cast(value);
      } catch (NoSuchFieldException ignored) {
      } catch (IllegalAccessException e) {
        throw new AssertionError();
      }
    }

    // Didn't find the field we wanted. As a last gasp attempt, try to find the value on a delegate.
    if (!fieldName.equals("delegate")) {
      Object delegate = readFieldOrNull(instance, Object.class, "delegate");
      if (delegate != null) return readFieldOrNull(delegate, fieldType, fieldName);
    }

    return null;
  }

  public SSLContext getSSLContext() {
    String jvmVersion = System.getProperty("java.specification.version");
    if ("1.7".equals(jvmVersion)) {
      try {
        // JDK 1.7 (public version) only support > TLSv1 with named protocols
        return SSLContext.getInstance("TLSv1.2");
      } catch (NoSuchAlgorithmException e) {
        // fallback to TLS
      }
    }

    try {
      return SSLContext.getInstance("TLS");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("No TLS provider", e);
    }
  }

  public void configureSslSocketFactory(SSLSocketFactory socketFactory) {
  }
}
