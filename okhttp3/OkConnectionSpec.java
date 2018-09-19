/*
 * Copyright (C) 2014 Square, Inc.
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


import javax.net.ssl.SSLSocket;

/**
 * Specifies configuration for the socket connection that HTTP traffic travels through. For {@code
 * https:} URLs, this includes the TLS version and cipher suites to use when negotiating a secure
 * connection.
 *
 * <p>The TLS versions configured in a connection spec are only be used if they are also enabled in
 * the SSL socket. For example, if an SSL socket does not have TLS 1.3 enabled, it will not be used
 * even if it is present on the connection spec. The same policy also applies to cipher suites.
 *
 * <p>Use {Builder#allEnabledTlsVersions()} and {Builder#allEnabledCipherSuites} to
 * defer all feature selection to the underlying SSL socket.
 */
public final class OkConnectionSpec {
  /** A modern TLS connection with extensions like SNI and ALPN available. */
  public static final OkConnectionSpec TLS = new Builder(true)
      .build();

  /** Unencrypted, unauthenticated connections for {@code http:} URLs. */
  public static final OkConnectionSpec CLEARTEXT = new Builder(false).build();

  final boolean tls;

  OkConnectionSpec(Builder builder) {
    this.tls = builder.tls;
  }

  public boolean isTls() {
    return tls;
  }


  /**
   * Returns {@code true} if the socket, as currently configured, supports this connection spec. In
   * order for a socket to be compatible the enabled cipher suites and protocols must intersect.
   *
   */
  public boolean isCompatible(SSLSocket socket) {
    if (!tls) {
      return false;
    }

    return true;
  }

  @Override public boolean equals(Object other) {
    if (!(other instanceof OkConnectionSpec)) return false;
    if (other == this) return true;

    OkConnectionSpec that = (OkConnectionSpec) other;
    if (this.tls != that.tls) return false;

    return true;
  }

  @Override public int hashCode() {
    int result = 17;
    if (tls) {
      result += 1;
    }
    return result;
  }

  @Override public String toString() {
    if (!tls) {
      return "ConnectionSpec()";
    }

    return "ConnectionSpec(TLS)";
  }

  public static final class Builder {
    boolean tls;

    Builder(boolean tls) {
      this.tls = tls;
    }

    public OkConnectionSpec build() {
      return new OkConnectionSpec(this);
    }
  }
}
