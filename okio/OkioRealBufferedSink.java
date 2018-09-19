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
package okio;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

final class OkioRealBufferedSink implements OkioBufferedSink {
  public final OkioBuffer buffer = new OkioBuffer();
  public final OkioSink sink;
  boolean closed;

  OkioRealBufferedSink(OkioSink sink) {
    if (sink == null) throw new NullPointerException("sink == null");
    this.sink = sink;
  }

  @Override public OkioBuffer buffer() {
    return buffer;
  }

  @Override public void write(OkioBuffer source, long byteCount)
      throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.write(source, byteCount);
    emitCompleteSegments();
  }

  @Override public OkioBufferedSink write(OkioByteString byteString) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.write(byteString);
    return emitCompleteSegments();
  }

  @Override public OkioBufferedSink writeUtf8(String string) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeUtf8(string);
    return emitCompleteSegments();
  }

  @Override public OkioBufferedSink writeUtf8(String string, int beginIndex, int endIndex)
      throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeUtf8(string, beginIndex, endIndex);
    return emitCompleteSegments();
  }

  @Override public OkioBufferedSink writeUtf8CodePoint(int codePoint) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeUtf8CodePoint(codePoint);
    return emitCompleteSegments();
  }

  @Override public OkioBufferedSink writeString(String string, Charset charset) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeString(string, charset);
    return emitCompleteSegments();
  }

  @Override public OkioBufferedSink writeString(String string, int beginIndex, int endIndex,
                                                Charset charset) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeString(string, beginIndex, endIndex, charset);
    return emitCompleteSegments();
  }

  @Override public OkioBufferedSink write(byte[] source) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.write(source);
    return emitCompleteSegments();
  }

  @Override public OkioBufferedSink write(byte[] source, int offset, int byteCount) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.write(source, offset, byteCount);
    return emitCompleteSegments();
  }

  @Override public int write(ByteBuffer source) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    int result = buffer.write(source);
    emitCompleteSegments();
    return result;
  }

  @Override public long writeAll(OkioSource source) throws IOException {
    if (source == null) throw new IllegalArgumentException("source == null");
    long totalBytesRead = 0;
    for (long readCount; (readCount = source.read(buffer, OkioSegment.SIZE)) != -1; ) {
      totalBytesRead += readCount;
      emitCompleteSegments();
    }
    return totalBytesRead;
  }

  @Override public OkioBufferedSink write(OkioSource source, long byteCount) throws IOException {
    while (byteCount > 0) {
      long read = source.read(buffer, byteCount);
      if (read == -1) throw new EOFException();
      byteCount -= read;
      emitCompleteSegments();
    }
    return this;
  }

  @Override public OkioBufferedSink writeByte(int b) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeByte(b);
    return emitCompleteSegments();
  }

  @Override public OkioBufferedSink writeShort(int s) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeShort(s);
    return emitCompleteSegments();
  }

  @Override public OkioBufferedSink writeShortLe(int s) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeShortLe(s);
    return emitCompleteSegments();
  }

  @Override public OkioBufferedSink writeInt(int i) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeInt(i);
    return emitCompleteSegments();
  }

  @Override public OkioBufferedSink writeIntLe(int i) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeIntLe(i);
    return emitCompleteSegments();
  }

  @Override public OkioBufferedSink writeLong(long v) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeLong(v);
    return emitCompleteSegments();
  }

  @Override public OkioBufferedSink writeLongLe(long v) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeLongLe(v);
    return emitCompleteSegments();
  }

  @Override public OkioBufferedSink writeDecimalLong(long v) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeDecimalLong(v);
    return emitCompleteSegments();
  }

  @Override public OkioBufferedSink writeHexadecimalUnsignedLong(long v) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeHexadecimalUnsignedLong(v);
    return emitCompleteSegments();
  }

  @Override public OkioBufferedSink emitCompleteSegments() throws IOException {
    if (closed) throw new IllegalStateException("closed");
    long byteCount = buffer.completeSegmentByteCount();
    if (byteCount > 0) sink.write(buffer, byteCount);
    return this;
  }

  @Override public OkioBufferedSink emit() throws IOException {
    if (closed) throw new IllegalStateException("closed");
    long byteCount = buffer.size();
    if (byteCount > 0) sink.write(buffer, byteCount);
    return this;
  }

  @Override public OutputStream outputStream() {
    return new OutputStream() {
      @Override public void write(int b) throws IOException {
        if (closed) throw new IOException("closed");
        buffer.writeByte((byte) b);
        emitCompleteSegments();
      }

      @Override public void write(byte[] data, int offset, int byteCount) throws IOException {
        if (closed) throw new IOException("closed");
        buffer.write(data, offset, byteCount);
        emitCompleteSegments();
      }

      @Override public void flush() throws IOException {
        // For backwards compatibility, a flush() on a closed stream is a no-op.
        if (!closed) {
          OkioRealBufferedSink.this.flush();
        }
      }

      @Override public void close() throws IOException {
        OkioRealBufferedSink.this.close();
      }

      @Override public String toString() {
        return OkioRealBufferedSink.this + ".outputStream()";
      }
    };
  }

  @Override public void flush() throws IOException {
    if (closed) throw new IllegalStateException("closed");
    if (buffer.size > 0) {
      sink.write(buffer, buffer.size);
    }
    sink.flush();
  }

  @Override public boolean isOpen() {
    return !closed;
  }

  @Override public void close() throws IOException {
    if (closed) return;

    // Emit buffered data to the underlying sink. If this fails, we still need
    // to close the sink; otherwise we risk leaking resources.
    Throwable thrown = null;
    try {
      if (buffer.size > 0) {
        sink.write(buffer, buffer.size);
      }
    } catch (Throwable e) {
      thrown = e;
    }

    try {
      sink.close();
    } catch (Throwable e) {
      if (thrown == null) thrown = e;
    }
    closed = true;

    if (thrown != null) OkioUtil.sneakyRethrow(thrown);
  }

  @Override public OkioTimeout timeout() {
    return sink.timeout();
  }

  @Override public String toString() {
    return "buffer(" + sink + ")";
  }
}
