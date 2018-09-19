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
package okhttp3.internal.http2;

import java.io.IOException;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHeaders;
import okhttp3.OkInterceptor;
import okhttp3.OkHttpClient;
import okhttp3.OkProtocol;
import okhttp3.OkRequest;
import okhttp3.OkResponse;
import okhttp3.OkResponseBody;
import okhttp3.internal.OkInternal;
import okhttp3.internal.OkUtil;
import okhttp3.internal.connection.OkStreamAllocation;
import okhttp3.internal.http.OkHttpCodec;
import okhttp3.internal.http.OkHttpHeaders;
import okhttp3.internal.http.OkRealResponseBody;
import okhttp3.internal.http.OkRequestLine;
import okhttp3.internal.http.OkStatusLine;
import okio.OkioBuffer;
import okio.OkioByteString;
import okio.OkioForwardingSource;
import okio.Okio;
import okio.OkioSink;
import okio.OkioSource;

import static okhttp3.internal.http.OkStatusLine.HTTP_CONTINUE;
import static okhttp3.internal.http2.OkHeader.RESPONSE_STATUS;
import static okhttp3.internal.http2.OkHeader.TARGET_AUTHORITY;
import static okhttp3.internal.http2.OkHeader.TARGET_METHOD;
import static okhttp3.internal.http2.OkHeader.TARGET_PATH;
import static okhttp3.internal.http2.OkHeader.TARGET_SCHEME;

/** Encode requests and responses using HTTP/2 frames. */
public final class OkHttp2Codec implements OkHttpCodec {
  private static final OkioByteString CONNECTION = OkioByteString.encodeUtf8("connection");
  private static final OkioByteString HOST = OkioByteString.encodeUtf8("host");
  private static final OkioByteString KEEP_ALIVE = OkioByteString.encodeUtf8("keep-alive");
  private static final OkioByteString PROXY_CONNECTION = OkioByteString.encodeUtf8("proxy-connection");
  private static final OkioByteString TRANSFER_ENCODING = OkioByteString.encodeUtf8("transfer-encoding");
  private static final OkioByteString TE = OkioByteString.encodeUtf8("te");
  private static final OkioByteString ENCODING = OkioByteString.encodeUtf8("encoding");
  private static final OkioByteString UPGRADE = OkioByteString.encodeUtf8("upgrade");

  /** See http://tools.ietf.org/html/draft-ietf-httpbis-http2-09#section-8.1.3. */
  private static final List<OkioByteString> HTTP_2_SKIPPED_REQUEST_HEADERS = OkUtil.immutableList(
      CONNECTION,
      HOST,
      KEEP_ALIVE,
      PROXY_CONNECTION,
      TE,
      TRANSFER_ENCODING,
      ENCODING,
      UPGRADE,
      TARGET_METHOD,
      TARGET_PATH,
      TARGET_SCHEME,
      TARGET_AUTHORITY);
  private static final List<OkioByteString> HTTP_2_SKIPPED_RESPONSE_HEADERS = OkUtil.immutableList(
      CONNECTION,
      HOST,
      KEEP_ALIVE,
      PROXY_CONNECTION,
      TE,
      TRANSFER_ENCODING,
      ENCODING,
      UPGRADE);

  private final OkInterceptor.Chain chain;
  final OkStreamAllocation streamAllocation;
  private final OkHttp2Connection connection;
  private OkHttp2Stream stream;
  private final OkProtocol protocol;

  public OkHttp2Codec(OkHttpClient client, OkInterceptor.Chain chain, OkStreamAllocation streamAllocation,
                      OkHttp2Connection connection) {
    this.chain = chain;
    this.streamAllocation = streamAllocation;
    this.connection = connection;
    this.protocol = client.protocols().contains(OkProtocol.H2_PRIOR_KNOWLEDGE)
        ? OkProtocol.H2_PRIOR_KNOWLEDGE
        : OkProtocol.HTTP_2;
  }

  @Override public OkioSink createRequestBody(OkRequest request, long contentLength) {
    return stream.getSink();
  }

  @Override public void writeRequestHeaders(OkRequest request) throws IOException {
    if (stream != null) return;

    boolean hasRequestBody = request.body() != null;
    List<OkHeader> requestHeaders = http2HeadersList(request);
    stream = connection.newStream(requestHeaders, hasRequestBody);
    stream.readTimeout().timeout(chain.readTimeoutMillis(), TimeUnit.MILLISECONDS);
    stream.writeTimeout().timeout(chain.writeTimeoutMillis(), TimeUnit.MILLISECONDS);
  }

  @Override public void flushRequest() throws IOException {
    connection.flush();
  }

  @Override public void finishRequest() throws IOException {
    stream.getSink().close();
  }

  @Override public OkResponse.Builder readResponseHeaders(boolean expectContinue) throws IOException {
    List<OkHeader> headers = stream.takeResponseHeaders();
    OkResponse.Builder responseBuilder = readHttp2HeadersList(headers, protocol);
    if (expectContinue && OkInternal.instance.code(responseBuilder) == HTTP_CONTINUE) {
      return null;
    }
    return responseBuilder;
  }

  public static List<OkHeader> http2HeadersList(OkRequest request) {
    OkHeaders headers = request.headers();
    List<OkHeader> result = new ArrayList<>(headers.size() + 4);
    result.add(new OkHeader(TARGET_METHOD, request.method()));
    result.add(new OkHeader(TARGET_PATH, OkRequestLine.requestPath(request.url())));
    String host = request.header("Host");
    if (host != null) {
      result.add(new OkHeader(TARGET_AUTHORITY, host)); // Optional.
    }
    result.add(new OkHeader(TARGET_SCHEME, request.url().scheme()));

    for (int i = 0, size = headers.size(); i < size; i++) {
      // header names must be lowercase.
      OkioByteString name = OkioByteString.encodeUtf8(headers.name(i).toLowerCase(Locale.US));
      if (!HTTP_2_SKIPPED_REQUEST_HEADERS.contains(name)) {
        result.add(new OkHeader(name, headers.value(i)));
      }
    }
    return result;
  }

  /** Returns headers for a name value block containing an HTTP/2 response. */
  public static OkResponse.Builder readHttp2HeadersList(List<OkHeader> headerBlock,
                                                        OkProtocol protocol) throws IOException {
    OkStatusLine statusLine = null;
    OkHeaders.Builder headersBuilder = new OkHeaders.Builder();
    for (int i = 0, size = headerBlock.size(); i < size; i++) {
      OkHeader header = headerBlock.get(i);

      // If there were multiple header blocks they will be delimited by nulls. Discard existing
      // header blocks if the existing header block is a '100 Continue' intermediate response.
      if (header == null) {
        if (statusLine != null && statusLine.code == HTTP_CONTINUE) {
          statusLine = null;
          headersBuilder = new OkHeaders.Builder();
        }
        continue;
      }

      OkioByteString name = header.name;
      String value = header.value.utf8();
      if (name.equals(RESPONSE_STATUS)) {
        statusLine = OkStatusLine.parse("HTTP/1.1 " + value);
      } else if (!HTTP_2_SKIPPED_RESPONSE_HEADERS.contains(name)) {
        OkInternal.instance.addLenient(headersBuilder, name.utf8(), value);
      }
    }
    if (statusLine == null) throw new ProtocolException("Expected ':status' header not present");

    return new OkResponse.Builder()
        .protocol(protocol)
        .code(statusLine.code)
        .message(statusLine.message)
        .headers(headersBuilder.build());
  }

  @Override public OkResponseBody openResponseBody(OkResponse response) throws IOException {
    streamAllocation.eventListener.responseBodyStart(streamAllocation.call);
    String contentType = response.header("Content-Type");
    long contentLength = OkHttpHeaders.contentLength(response);
    OkioSource source = new StreamFinishingSource(stream.getSource());
    return new OkRealResponseBody(contentType, contentLength, Okio.buffer(source));
  }

  @Override public void cancel() {
    if (stream != null) stream.closeLater(OkErrorCode.CANCEL);
  }

  class StreamFinishingSource extends OkioForwardingSource {
    boolean completed = false;
    long bytesRead = 0;

    StreamFinishingSource(OkioSource delegate) {
      super(delegate);
    }

    @Override public long read(OkioBuffer sink, long byteCount) throws IOException {
      try {
        long read = delegate().read(sink, byteCount);
        if (read > 0) {
          bytesRead += read;
        }
        return read;
      } catch (IOException e) {
        endOfInput(e);
        throw e;
      }
    }

    @Override public void close() throws IOException {
      super.close();
      endOfInput(null);
    }

    private void endOfInput(IOException e) {
      if (completed) return;
      completed = true;
      streamAllocation.streamFinished(false, OkHttp2Codec.this, bytesRead, e);
    }
  }
}
