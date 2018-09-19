/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3.internal.connection;

import java.io.IOException;
import okhttp3.OkInterceptor;
import okhttp3.OkHttpClient;
import okhttp3.OkRequest;
import okhttp3.OkResponse;
import okhttp3.internal.http.OkHttpCodec;
import okhttp3.internal.http.OkRealInterceptorChain;

/** Opens a connection to the target server and proceeds to the next interceptor. */
public final class OkConnectInterceptor implements OkInterceptor {
  public final OkHttpClient client;

  public OkConnectInterceptor(OkHttpClient client) {
    this.client = client;
  }

  @Override public OkResponse intercept(Chain chain) throws IOException {
    OkRealInterceptorChain realChain = (OkRealInterceptorChain) chain;
    OkRequest request = realChain.request();
    OkStreamAllocation streamAllocation = realChain.streamAllocation();

    // We need the network to satisfy this request. Possibly for validating a conditional GET.
    boolean doExtensiveHealthChecks = !request.method().equals("GET");
    OkHttpCodec httpCodec = streamAllocation.newStream(client, chain, doExtensiveHealthChecks);
    OkRealConnection connection = streamAllocation.connection();

    return realChain.proceed(request, streamAllocation, httpCodec, connection);
  }
}
