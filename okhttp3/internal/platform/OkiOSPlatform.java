/*
 * Copyright (C) 2016 Square, Inc.
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
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkProtocol;
import okhttp3.internal.OkUtil;

class OkiOSPlatform extends OkPlatform {
  private static final int MAX_LOG_LENGTH = 4000;

  private final CloseGuard closeGuard = CloseGuard.get();

  OkiOSPlatform() {
  }

  @Override public void connectSocket(Socket socket, InetSocketAddress address,
                                      int connectTimeout) throws IOException {
    try {
      socket.connect(address, connectTimeout);
    } catch (AssertionError e) {
      if (OkUtil.isAndroidGetsocknameError(e)) throw new IOException(e);
      throw e;
    } catch (SecurityException e) {
      // Before android 4.3, socket.connect could throw a SecurityException
      // if opening a socket resulted in an EACCES error.
      IOException ioException = new IOException("Exception in connect");
      ioException.initCause(e);
      throw ioException;
    } catch (ClassCastException e) {
      // On android 8.0, socket.connect throws a ClassCastException due to a bug
      // see https://issuetracker.google.com/issues/63649622
      throw e;
    }
  }

  @Override protected X509TrustManager trustManager(SSLSocketFactory sslSocketFactory) {
    return null;
  }

  @Override public void configureTlsExtensions(
          SSLSocket sslSocket, String hostname, List<OkProtocol> protocols) {
  }

  @Override public String getSelectedProtocol(SSLSocket socket) {
    return null;
  }

  @Override public void log(int level, String message, Throwable t) {
    if (t != null) message = message + '\n' + t;

    // Split by line, then ensure each line can fit into Log's maximum length.
    for (int i = 0, length = message.length(); i < length; i++) {
      int newline = message.indexOf('\n', i);
      newline = newline != -1 ? newline : length;
      do {
        int end = Math.min(newline, i + MAX_LOG_LENGTH);
        System.out.println("OkHttp: " + message.substring(i, end));
        i = end;
      } while (i < newline);
    }
  }

  @Override public Object getStackTraceForCloseable(String closer) {
    return closeGuard.createAndOpen(closer);
  }

  @Override public void logCloseableLeak(String message, Object stackTrace) {
    boolean reported = closeGuard.warnIfOpen(stackTrace);
    if (!reported) {
      // Unable to report via CloseGuard. As a last-ditch effort, send it to the logger.
      log(WARN, message, null);
    }
  }

  @Override public boolean isCleartextTrafficPermitted(String hostname) {
    return true;
  }

  public static OkPlatform buildIfSupported() {
    return new OkiOSPlatform();
  }

  /**
   * Provides access to the internal dalvik.system.CloseGuard class. Android uses this in
   * combination with android.os.StrictMode to report on leaked java.io.Closeable's. Available since
   * Android API 11.
   */
  static final class CloseGuard {
    private final Method getMethod;
    private final Method openMethod;
    private final Method warnIfOpenMethod;

    CloseGuard(Method getMethod, Method openMethod, Method warnIfOpenMethod) {
      this.getMethod = getMethod;
      this.openMethod = openMethod;
      this.warnIfOpenMethod = warnIfOpenMethod;
    }

    Object createAndOpen(String closer) {
      if (getMethod != null) {
        try {
          Object closeGuardInstance = getMethod.invoke(null);
          openMethod.invoke(closeGuardInstance, closer);
          return closeGuardInstance;
        } catch (Exception ignored) {
        }
      }
      return null;
    }

    boolean warnIfOpen(Object closeGuardInstance) {
      boolean reported = false;
      if (closeGuardInstance != null) {
        try {
          warnIfOpenMethod.invoke(closeGuardInstance);
          reported = true;
        } catch (Exception ignored) {
        }
      }
      return reported;
    }

    static CloseGuard get() {
      Method getMethod;
      Method openMethod;
      Method warnIfOpenMethod;

      try {
        Class<?> closeGuardClass = Class.forName("dalvik.system.CloseGuard");
        getMethod = closeGuardClass.getMethod("get");
        openMethod = closeGuardClass.getMethod("open", String.class);
        warnIfOpenMethod = closeGuardClass.getMethod("warnIfOpen");
      } catch (Exception ignored) {
        getMethod = null;
        openMethod = null;
        warnIfOpenMethod = null;
      }
      return new CloseGuard(getMethod, openMethod, warnIfOpenMethod);
    }
  }
}
