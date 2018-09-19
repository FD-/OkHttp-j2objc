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
package okhttp3.internal.huc;

import java.io.IOException;
import okhttp3.internal.http.OkUnrepeatableRequestBody;
import okio.OkioBuffer;
import okio.OkioBufferedSink;
import okio.Okio;
import okio.OkioPipe;

/**
 * This request body streams bytes from an application thread to an OkHttp dispatcher thread via a
 * pipe. Because the data is not buffered it can only be transmitted once.
 */
final class OkStreamedRequestBody extends OkOutputStreamRequestBody implements OkUnrepeatableRequestBody {
  private final OkioPipe pipe = new OkioPipe(8192);

  OkStreamedRequestBody(long expectedContentLength) {
    initOutputStream(Okio.buffer(pipe.sink()), expectedContentLength);
  }

  @Override public void writeTo(OkioBufferedSink sink) throws IOException {
    OkioBuffer buffer = new OkioBuffer();
    while (pipe.source().read(buffer, 8192) != -1L) {
      sink.write(buffer, buffer.size());
    }
  }
}
