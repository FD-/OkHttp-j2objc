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
package okhttp3.internal;

import java.net.Socket;
import javax.net.ssl.SSLSocket;
import okhttp3.OkAddress;
import okhttp3.OkCall;
import okhttp3.OkConnectionPool;
import okhttp3.OkConnectionSpec;
import okhttp3.OkHeaders;
import okhttp3.OkHttpClient;
import okhttp3.OkRequest;
import okhttp3.OkResponse;
import okhttp3.OkRoute;
import okhttp3.internal.cache.OkInternalCache;
import okhttp3.internal.connection.OkRealConnection;
import okhttp3.internal.connection.OkRouteDatabase;
import okhttp3.internal.connection.OkStreamAllocation;

/**
 * Escalate internal APIs in {@code okhttp3} so they can be used from OkHttp's implementation
 * packages. The only implementation of this interface is in {@link OkHttpClient}.
 */
public abstract class OkInternal {

  public static void initializeInstanceForTests() {
    // Needed in tests to ensure that the instance is actually pointing to something.
    new OkHttpClient();
  }

  public static OkInternal instance;

  public abstract void addLenient(OkHeaders.Builder builder, String line);

  public abstract void addLenient(OkHeaders.Builder builder, String name, String value);

  public abstract void setCache(OkHttpClient.Builder builder, OkInternalCache internalCache);

  public abstract OkRealConnection get(OkConnectionPool pool, OkAddress address,
                                       OkStreamAllocation streamAllocation, OkRoute route);

  public abstract boolean equalsNonHost(OkAddress a, OkAddress b);

  public abstract Socket deduplicate(
          OkConnectionPool pool, OkAddress address, OkStreamAllocation streamAllocation);

  public abstract void put(OkConnectionPool pool, OkRealConnection connection);

  public abstract boolean connectionBecameIdle(OkConnectionPool pool, OkRealConnection connection);

  public abstract OkRouteDatabase routeDatabase(OkConnectionPool connectionPool);

  public abstract int code(OkResponse.Builder responseBuilder);

  public abstract void apply(OkConnectionSpec tlsConfiguration, SSLSocket sslSocket,
                             boolean isFallback);

  public abstract boolean isInvalidHttpUrlHost(IllegalArgumentException e);

  public abstract OkStreamAllocation streamAllocation(OkCall call);

  public abstract OkCall newWebSocketCall(OkHttpClient client, OkRequest request);
}
