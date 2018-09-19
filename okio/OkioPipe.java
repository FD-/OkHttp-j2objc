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
package okio;

import java.io.IOException;

import com.google.j2objc.annotations.WeakOuter;

/**
 * A source and a sink that are attached. The sink's output is the source's input. Typically each
 * is accessed by its own thread: a producer thread writes data to the sink and a consumer thread
 * reads data from the source.
 *
 * <p>This class uses a buffer to decouple source and sink. This buffer has a user-specified maximum
 * size. When a producer thread outruns its consumer the buffer fills up and eventually writes to
 * the sink will block until the consumer has caught up. Symmetrically, if a consumer outruns its
 * producer reads block until there is data to be read. Limits on the amount of time spent waiting
 * for the other party can be configured with {@linkplain OkioTimeout timeouts} on the source and the
 * sink.
 *
 * <p>When the sink is closed, source reads will continue to complete normally until the buffer has
 * been exhausted. At that point reads will return -1, indicating the end of the stream. But if the
 * source is closed first, writes to the sink will immediately fail with an {@link IOException}.
 */
public final class OkioPipe {
  final long maxBufferSize;
  final OkioBuffer buffer = new OkioBuffer();
  boolean sinkClosed;
  boolean sourceClosed;
  private final OkioSink sink = new PipeSink();
  private final OkioSource source = new PipeSource();

  public OkioPipe(long maxBufferSize) {
    if (maxBufferSize < 1L) {
      throw new IllegalArgumentException("maxBufferSize < 1: " + maxBufferSize);
    }
    this.maxBufferSize = maxBufferSize;
  }

  public OkioSource source() {
    return source;
  }

  public OkioSink sink() {
    return sink;
  }

  @WeakOuter
  final class PipeSink implements OkioSink {
    final OkioTimeout timeout = new OkioTimeout();

    @Override public void write(OkioBuffer source, long byteCount) throws IOException {
      synchronized (buffer) {
        if (sinkClosed) throw new IllegalStateException("closed");

        while (byteCount > 0) {
          if (sourceClosed) throw new IOException("source is closed");

          long bufferSpaceAvailable = maxBufferSize - buffer.size();
          if (bufferSpaceAvailable == 0) {
            timeout.waitUntilNotified(buffer); // Wait until the source drains the buffer.
            continue;
          }

          long bytesToWrite = Math.min(bufferSpaceAvailable, byteCount);
          buffer.write(source, bytesToWrite);
          byteCount -= bytesToWrite;
          buffer.notifyAll(); // Notify the source that it can resume reading.
        }
      }
    }

    @Override public void flush() throws IOException {
      synchronized (buffer) {
        if (sinkClosed) throw new IllegalStateException("closed");
        if (sourceClosed && buffer.size() > 0) throw new IOException("source is closed");
      }
    }

    @Override public void close() throws IOException {
      synchronized (buffer) {
        if (sinkClosed) return;
        if (sourceClosed && buffer.size() > 0) throw new IOException("source is closed");
        sinkClosed = true;
        buffer.notifyAll(); // Notify the source that no more bytes are coming.
      }
    }

    @Override public OkioTimeout timeout() {
      return timeout;
    }
  }

  @WeakOuter
  final class PipeSource implements OkioSource {
    final OkioTimeout timeout = new OkioTimeout();

    @Override public long read(OkioBuffer sink, long byteCount) throws IOException {
      synchronized (buffer) {
        if (sourceClosed) throw new IllegalStateException("closed");

        while (buffer.size() == 0) {
          if (sinkClosed) return -1L;
          timeout.waitUntilNotified(buffer); // Wait until the sink fills the buffer.
        }

        long result = buffer.read(sink, byteCount);
        buffer.notifyAll(); // Notify the sink that it can resume writing.
        return result;
      }
    }

    @Override public void close() throws IOException {
      synchronized (buffer) {
        sourceClosed = true;
        buffer.notifyAll(); // Notify the sink that no more bytes are desired.
      }
    }

    @Override public OkioTimeout timeout() {
      return timeout;
    }
  }
}
