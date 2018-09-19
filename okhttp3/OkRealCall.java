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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.internal.OkNamedRunnable;
import okhttp3.internal.cache.OkCacheInterceptor;
import okhttp3.internal.connection.OkConnectInterceptor;
import okhttp3.internal.connection.OkStreamAllocation;
import okhttp3.internal.http.OkBridgeInterceptor;
import okhttp3.internal.http.OkCallServerInterceptor;
import okhttp3.internal.http.OkRealInterceptorChain;
import okhttp3.internal.http.OkRetryAndFollowUpInterceptor;
import okhttp3.internal.platform.OkPlatform;

import static okhttp3.internal.platform.OkPlatform.INFO;

final class OkRealCall implements OkCall {
  final OkHttpClient client;
  final OkRetryAndFollowUpInterceptor retryAndFollowUpInterceptor;

  /**
   * There is a cycle between the {@link OkCall} and {@link OkEventListener} that makes this awkward.
   * This will be set after we create the call instance then create the event listener instance.
   */
  private OkEventListener eventListener;

  /** The application's original request unadulterated by redirects or auth headers. */
  final OkRequest originalRequest;
  final boolean forWebSocket;

  // Guarded by this.
  private boolean executed;

  private OkRealCall(OkHttpClient client, OkRequest originalRequest, boolean forWebSocket) {
    this.client = client;
    this.originalRequest = originalRequest;
    this.forWebSocket = forWebSocket;
    this.retryAndFollowUpInterceptor = new OkRetryAndFollowUpInterceptor(client, forWebSocket);
  }

  static OkRealCall newRealCall(OkHttpClient client, OkRequest originalRequest, boolean forWebSocket) {
    // Safely publish the Call instance to the EventListener.
    OkRealCall call = new OkRealCall(client, originalRequest, forWebSocket);
    call.eventListener = client.eventListenerFactory().create(call);
    return call;
  }

  @Override public OkRequest request() {
    return originalRequest;
  }

  @Override public OkResponse execute() throws IOException {
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already Executed");
      executed = true;
    }
    captureCallStackTrace();
    eventListener.callStart(this);
    try {
      client.dispatcher().executed(this);
      OkResponse result = getResponseWithInterceptorChain();
      if (result == null) throw new IOException("Canceled");
      return result;
    } catch (IOException e) {
      eventListener.callFailed(this, e);
      throw e;
    } finally {
      client.dispatcher().finished(this);
    }
  }

  private void captureCallStackTrace() {
    Object callStackTrace = OkPlatform.get().getStackTraceForCloseable("response.body().close()");
    retryAndFollowUpInterceptor.setCallStackTrace(callStackTrace);
  }

  @Override public void enqueue(OkCallback responseCallback) {
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already Executed");
      executed = true;
    }
    captureCallStackTrace();
    eventListener.callStart(this);
    client.dispatcher().enqueue(new AsyncCall(responseCallback));
  }

  @Override public void cancel() {
    retryAndFollowUpInterceptor.cancel();
  }

  @Override public synchronized boolean isExecuted() {
    return executed;
  }

  @Override public boolean isCanceled() {
    return retryAndFollowUpInterceptor.isCanceled();
  }

  @SuppressWarnings("CloneDoesntCallSuperClone") // We are a final type & this saves clearing state.
  @Override public OkRealCall clone() {
    return OkRealCall.newRealCall(client, originalRequest, forWebSocket);
  }

  OkStreamAllocation streamAllocation() {
    return retryAndFollowUpInterceptor.streamAllocation();
  }

  final class AsyncCall extends OkNamedRunnable {
    private final OkCallback responseCallback;

    AsyncCall(OkCallback responseCallback) {
      super("OkHttp %s", redactedUrl());
      this.responseCallback = responseCallback;
    }

    String host() {
      return originalRequest.url().host();
    }

    OkRequest request() {
      return originalRequest;
    }

    OkRealCall get() {
      return OkRealCall.this;
    }

    @Override protected void execute() {
      boolean signalledCallback = false;
      try {
        OkResponse response = getResponseWithInterceptorChain();
        if (retryAndFollowUpInterceptor.isCanceled()) {
          signalledCallback = true;
          responseCallback.onFailure(OkRealCall.this, new IOException("Canceled"));
        } else {
          signalledCallback = true;
          responseCallback.onResponse(OkRealCall.this, response);
        }
      } catch (IOException e) {
        if (signalledCallback) {
          // Do not signal the callback twice!
          OkPlatform.get().log(INFO, "Callback failure for " + toLoggableString(), e);
        } else {
          eventListener.callFailed(OkRealCall.this, e);
          responseCallback.onFailure(OkRealCall.this, e);
        }
      } finally {
        client.dispatcher().finished(this);
      }
    }
  }

  /**
   * Returns a string that describes this call. Doesn't include a full URL as that might contain
   * sensitive information.
   */
  String toLoggableString() {
    return (isCanceled() ? "canceled " : "")
        + (forWebSocket ? "web socket" : "call")
        + " to " + redactedUrl();
  }

  String redactedUrl() {
    return originalRequest.url().redact();
  }

  OkResponse getResponseWithInterceptorChain() throws IOException {
    // Build a full stack of interceptors.
    List<OkInterceptor> interceptors = new ArrayList<>();
    interceptors.addAll(client.interceptors());
    interceptors.add(retryAndFollowUpInterceptor);
    interceptors.add(new OkBridgeInterceptor(client.cookieJar()));
    interceptors.add(new OkCacheInterceptor(client.internalCache()));
    interceptors.add(new OkConnectInterceptor(client));
    if (!forWebSocket) {
      interceptors.addAll(client.networkInterceptors());
    }
    interceptors.add(new OkCallServerInterceptor(forWebSocket));

    OkInterceptor.Chain chain = new OkRealInterceptorChain(interceptors, null, null, null, 0,
        originalRequest, this, eventListener, client.connectTimeoutMillis(),
        client.readTimeoutMillis(), client.writeTimeoutMillis());

    return chain.proceed(originalRequest);
  }
}
