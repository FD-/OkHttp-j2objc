/*
 * Copyright (C) 2013 Square, Inc.
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

import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

import okhttp3.internal.http.OkHttpHeaders;
import okio.OkioBuffer;
import okio.OkioBufferedSource;

import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_MULT_CHOICE;
import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;
import static java.net.HttpURLConnection.HTTP_SEE_OTHER;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
import static okhttp3.internal.http.OkStatusLine.HTTP_PERM_REDIRECT;
import static okhttp3.internal.http.OkStatusLine.HTTP_TEMP_REDIRECT;

/**
 * An HTTP response. Instances of this class are not immutable: the response body is a one-shot
 * value that may be consumed only once and then closed. All other properties are immutable.
 *
 * <p>This class implements {@link Closeable}. Closing it simply closes its response body. See
 * {@link OkResponseBody} for an explanation and examples.
 */
public final class OkResponse implements Closeable {
  final OkRequest request;
  final OkProtocol protocol;
  final int code;
  final String message;
  final OkHeaders headers;
  final
  OkResponseBody body;
  final
  OkResponse networkResponse;
  final
  OkResponse cacheResponse;
  final
  OkResponse priorResponse;
  final long sentRequestAtMillis;
  final long receivedResponseAtMillis;

  private volatile OkCacheControl cacheControl; // Lazily initialized.

  OkResponse(Builder builder) {
    this.request = builder.request;
    this.protocol = builder.protocol;
    this.code = builder.code;
    this.message = builder.message;
    this.headers = builder.headers.build();
    this.body = builder.body;
    this.networkResponse = builder.networkResponse;
    this.cacheResponse = builder.cacheResponse;
    this.priorResponse = builder.priorResponse;
    this.sentRequestAtMillis = builder.sentRequestAtMillis;
    this.receivedResponseAtMillis = builder.receivedResponseAtMillis;
  }

  /**
   * The wire-level request that initiated this HTTP response. This is not necessarily the same
   * request issued by the application:
   *
   * <ul>
   *     <li>It may be transformed by the HTTP client. For example, the client may copy headers like
   *         {@code Content-Length} from the request body.
   *     <li>It may be the request generated in response to an HTTP redirect or authentication
   *         challenge. In this case the request URL may be different than the initial request URL.
   * </ul>
   */
  public OkRequest request() {
    return request;
  }

  /**
   * Returns the HTTP protocol, such as {@link OkProtocol#HTTP_1_1} or {@link OkProtocol#HTTP_1_0}.
   */
  public OkProtocol protocol() {
    return protocol;
  }

  /** Returns the HTTP status code. */
  public int code() {
    return code;
  }

  /**
   * Returns true if the code is in [200..300), which means the request was successfully received,
   * understood, and accepted.
   */
  public boolean isSuccessful() {
    return code >= 200 && code < 300;
  }

  /** Returns the HTTP status message. */
  public String message() {
    return message;
  }

  public List<String> headers(String name) {
    return headers.values(name);
  }

  public String header(String name) {
    return header(name, null);
  }

  public String header(String name, String defaultValue) {
    String result = headers.get(name);
    return result != null ? result : defaultValue;
  }

  public OkHeaders headers() {
    return headers;
  }

  /**
   * Peeks up to {@code byteCount} bytes from the response body and returns them as a new response
   * body. If fewer than {@code byteCount} bytes are in the response body, the full response body is
   * returned. If more than {@code byteCount} bytes are in the response body, the returned value
   * will be truncated to {@code byteCount} bytes.
   *
   * <p>It is an error to call this method after the body has been consumed.
   *
   * <p><strong>Warning:</strong> this method loads the requested bytes into memory. Most
   * applications should set a modest limit on {@code byteCount}, such as 1 MiB.
   */
  public OkResponseBody peekBody(long byteCount) throws IOException {
    OkioBufferedSource source = body.source();
    source.request(byteCount);
    OkioBuffer copy = source.buffer().clone();

    // There may be more than byteCount bytes in source.buffer(). If there is, return a prefix.
    OkioBuffer result;
    if (copy.size() > byteCount) {
      result = new OkioBuffer();
      result.write(copy, byteCount);
      copy.clear();
    } else {
      result = copy;
    }

    return OkResponseBody.create(body.contentType(), result.size(), result);
  }

  /**
   * Returns a non-null value if this response was passed to {@link OkCallback#onResponse} or returned
   * from {@link OkCall#execute()}. Response bodies must be {@linkplain OkResponseBody closed} and may
   * be consumed only once.
   *
   * <p>This always returns null on responses returned from {@link #cacheResponse}, {@link
   * #networkResponse}, and {@link #priorResponse()}.
   */
  public
  OkResponseBody body() {
    return body;
  }

  public Builder newBuilder() {
    return new Builder(this);
  }

  /** Returns true if this response redirects to another resource. */
  public boolean isRedirect() {
    switch (code) {
      case HTTP_PERM_REDIRECT:
      case HTTP_TEMP_REDIRECT:
      case HTTP_MULT_CHOICE:
      case HTTP_MOVED_PERM:
      case HTTP_MOVED_TEMP:
      case HTTP_SEE_OTHER:
        return true;
      default:
        return false;
    }
  }

  /**
   * Returns the raw response received from the network. Will be null if this response didn't use
   * the network, such as when the response is fully cached. The body of the returned response
   * should not be read.
   */
  public
  OkResponse networkResponse() {
    return networkResponse;
  }

  /**
   * Returns the raw response received from the cache. Will be null if this response didn't use the
   * cache. For conditional get requests the cache response and network response may both be
   * non-null. The body of the returned response should not be read.
   */
  public
  OkResponse cacheResponse() {
    return cacheResponse;
  }

  /**
   * Returns the response for the HTTP redirect or authorization challenge that triggered this
   * response, or null if this response wasn't triggered by an automatic retry. The body of the
   * returned response should not be read because it has already been consumed by the redirecting
   * client.
   */
  public
  OkResponse priorResponse() {
    return priorResponse;
  }

  /**
   * Returns the authorization challenges appropriate for this response's code. If the response code
   * is 401 unauthorized, this returns the "WWW-Authenticate" challenges. If the response code is
   * 407 proxy unauthorized, this returns the "Proxy-Authenticate" challenges. Otherwise this
   * returns an empty list of challenges.
   */
  public List<OkChallenge> challenges() {
    String responseField;
    if (code == HTTP_UNAUTHORIZED) {
      responseField = "WWW-Authenticate";
    } else if (code == HTTP_PROXY_AUTH) {
      responseField = "Proxy-Authenticate";
    } else {
      return Collections.emptyList();
    }
    return OkHttpHeaders.parseChallenges(headers(), responseField);
  }

  /**
   * Returns the cache control directives for this response. This is never null, even if this
   * response contains no {@code Cache-Control} header.
   */
  public OkCacheControl cacheControl() {
    OkCacheControl result = cacheControl;
    return result != null ? result : (cacheControl = OkCacheControl.parse(headers));
  }

  /**
   * Returns a {@linkplain System#currentTimeMillis() timestamp} taken immediately before OkHttp
   * transmitted the initiating request over the network. If this response is being served from the
   * cache then this is the timestamp of the original request.
   */
  public long sentRequestAtMillis() {
    return sentRequestAtMillis;
  }

  /**
   * Returns a {@linkplain System#currentTimeMillis() timestamp} taken immediately after OkHttp
   * received this response's headers from the network. If this response is being served from the
   * cache then this is the timestamp of the original response.
   */
  public long receivedResponseAtMillis() {
    return receivedResponseAtMillis;
  }

  /**
   * Closes the response body. Equivalent to {@code body().close()}.
   *
   * <p>It is an error to close a response that is not eligible for a body. This includes the
   * responses returned from {@link #cacheResponse}, {@link #networkResponse}, and {@link
   * #priorResponse()}.
   */
  @Override public void close() {
    if (body == null) {
      throw new IllegalStateException("response is not eligible for a body and must not be closed");
    }
    body.close();
  }

  @Override public String toString() {
    return "Response{protocol="
        + protocol
        + ", code="
        + code
        + ", message="
        + message
        + ", url="
        + request.url()
        + '}';
  }

  public static class Builder {
    OkRequest request;
    OkProtocol protocol;
    int code = -1;
    String message;
    OkHeaders.Builder headers;
    OkResponseBody body;
    OkResponse networkResponse;
    OkResponse cacheResponse;
    OkResponse priorResponse;
    long sentRequestAtMillis;
    long receivedResponseAtMillis;

    public Builder() {
      headers = new OkHeaders.Builder();
    }

    Builder(OkResponse response) {
      this.request = response.request;
      this.protocol = response.protocol;
      this.code = response.code;
      this.message = response.message;
      this.headers = response.headers.newBuilder();
      this.body = response.body;
      this.networkResponse = response.networkResponse;
      this.cacheResponse = response.cacheResponse;
      this.priorResponse = response.priorResponse;
      this.sentRequestAtMillis = response.sentRequestAtMillis;
      this.receivedResponseAtMillis = response.receivedResponseAtMillis;
    }

    public Builder request(OkRequest request) {
      this.request = request;
      return this;
    }

    public Builder protocol(OkProtocol protocol) {
      this.protocol = protocol;
      return this;
    }

    public Builder code(int code) {
      this.code = code;
      return this;
    }

    public Builder message(String message) {
      this.message = message;
      return this;
    }

    /**
     * Sets the header named {@code name} to {@code value}. If this request already has any headers
     * with that name, they are all replaced.
     */
    public Builder header(String name, String value) {
      headers.set(name, value);
      return this;
    }

    /**
     * Adds a header with {@code name} and {@code value}. Prefer this method for multiply-valued
     * headers like "Set-Cookie".
     */
    public Builder addHeader(String name, String value) {
      headers.add(name, value);
      return this;
    }

    public Builder removeHeader(String name) {
      headers.removeAll(name);
      return this;
    }

    /** Removes all headers on this builder and adds {@code headers}. */
    public Builder headers(OkHeaders headers) {
      this.headers = headers.newBuilder();
      return this;
    }

    public Builder body(OkResponseBody body) {
      this.body = body;
      return this;
    }

    public Builder networkResponse(OkResponse networkResponse) {
      if (networkResponse != null) checkSupportResponse("networkResponse", networkResponse);
      this.networkResponse = networkResponse;
      return this;
    }

    public Builder cacheResponse(OkResponse cacheResponse) {
      if (cacheResponse != null) checkSupportResponse("cacheResponse", cacheResponse);
      this.cacheResponse = cacheResponse;
      return this;
    }

    private void checkSupportResponse(String name, OkResponse response) {
      if (response.body != null) {
        throw new IllegalArgumentException(name + ".body != null");
      } else if (response.networkResponse != null) {
        throw new IllegalArgumentException(name + ".networkResponse != null");
      } else if (response.cacheResponse != null) {
        throw new IllegalArgumentException(name + ".cacheResponse != null");
      } else if (response.priorResponse != null) {
        throw new IllegalArgumentException(name + ".priorResponse != null");
      }
    }

    public Builder priorResponse(OkResponse priorResponse) {
      if (priorResponse != null) checkPriorResponse(priorResponse);
      this.priorResponse = priorResponse;
      return this;
    }

    private void checkPriorResponse(OkResponse response) {
      if (response.body != null) {
        throw new IllegalArgumentException("priorResponse.body != null");
      }
    }

    public Builder sentRequestAtMillis(long sentRequestAtMillis) {
      this.sentRequestAtMillis = sentRequestAtMillis;
      return this;
    }

    public Builder receivedResponseAtMillis(long receivedResponseAtMillis) {
      this.receivedResponseAtMillis = receivedResponseAtMillis;
      return this;
    }

    public OkResponse build() {
      if (request == null) throw new IllegalStateException("request == null");
      if (protocol == null) throw new IllegalStateException("protocol == null");
      if (code < 0) throw new IllegalStateException("code < 0: " + code);
      if (message == null) throw new IllegalStateException("message == null");
      return new OkResponse(this);
    }
  }
}
