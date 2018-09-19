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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;



import static okio.OkioUtil.checkOffsetAndCount;

/** Essential APIs for working with Okio. */
public final class Okio {
  static final Logger logger = Logger.getLogger(Okio.class.getName());

  private Okio() {
  }

  /**
   * Returns a new source that buffers reads from {@code source}. The returned
   * source will perform bulk reads into its in-memory buffer. Use this wherever
   * you read a source to get an ergonomic and efficient access to data.
   */
  public static OkioBufferedSource buffer(OkioSource source) {
    return new OkioRealBufferedSource(source);
  }

  /**
   * Returns a new sink that buffers writes to {@code sink}. The returned sink
   * will batch writes to {@code sink}. Use this wherever you write to a sink to
   * get an ergonomic and efficient access to data.
   */
  public static OkioBufferedSink buffer(OkioSink sink) {
    return new OkioRealBufferedSink(sink);
  }

  /** Returns a sink that writes to {@code out}. */
  public static OkioSink sink(OutputStream out) {
    return sink(out, new OkioTimeout());
  }

  private static OkioSink sink(final OutputStream out, final OkioTimeout timeout) {
    if (out == null) throw new IllegalArgumentException("out == null");
    if (timeout == null) throw new IllegalArgumentException("timeout == null");

    return new OkioSink() {
      @Override public void write(OkioBuffer source, long byteCount) throws IOException {
        checkOffsetAndCount(source.size, 0, byteCount);
        while (byteCount > 0) {
          timeout.throwIfReached();
          OkioSegment head = source.head;
          int toCopy = (int) Math.min(byteCount, head.limit - head.pos);
          out.write(head.data, head.pos, toCopy);

          head.pos += toCopy;
          byteCount -= toCopy;
          source.size -= toCopy;

          if (head.pos == head.limit) {
            source.head = head.pop();
            OkioSegmentPool.recycle(head);
          }
        }
      }

      @Override public void flush() throws IOException {
        out.flush();
      }

      @Override public void close() throws IOException {
        out.close();
      }

      @Override public OkioTimeout timeout() {
        return timeout;
      }

      @Override public String toString() {
        return "sink(" + out + ")";
      }
    };
  }

  /**
   * Returns a sink that writes to {@code socket}. Prefer this over {@link
   * #sink(OutputStream)} because this method honors timeouts. When the socket
   * write times out, the socket is asynchronously closed by a watchdog thread.
   */
  public static OkioSink sink(Socket socket) throws IOException {
    if (socket == null) throw new IllegalArgumentException("socket == null");
    if (socket.getOutputStream() == null) throw new IOException("socket's output stream == null");
    OkioAsyncTimeout timeout = timeout(socket);
    OkioSink sink = sink(socket.getOutputStream(), timeout);
    return timeout.sink(sink);
  }

  /** Returns a source that reads from {@code in}. */
  public static OkioSource source(InputStream in) {
    return source(in, new OkioTimeout());
  }

  private static OkioSource source(final InputStream in, final OkioTimeout timeout) {
    if (in == null) throw new IllegalArgumentException("in == null");
    if (timeout == null) throw new IllegalArgumentException("timeout == null");

    return new OkioSource() {
      @Override public long read(OkioBuffer sink, long byteCount) throws IOException {
        if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
        if (byteCount == 0) return 0;
        try {
          timeout.throwIfReached();
          OkioSegment tail = sink.writableSegment(1);
          int maxToCopy = (int) Math.min(byteCount, OkioSegment.SIZE - tail.limit);
          int bytesRead = in.read(tail.data, tail.limit, maxToCopy);
          if (bytesRead == -1) return -1;
          tail.limit += bytesRead;
          sink.size += bytesRead;
          return bytesRead;
        } catch (AssertionError e) {
          if (isAndroidGetsocknameError(e)) throw new IOException(e);
          throw e;
        }
      }

      @Override public void close() throws IOException {
        in.close();
      }

      @Override public OkioTimeout timeout() {
        return timeout;
      }

      @Override public String toString() {
        return "source(" + in + ")";
      }
    };
  }

  /** Returns a source that reads from {@code file}. */
  public static OkioSource source(File file) throws FileNotFoundException {
    if (file == null) throw new IllegalArgumentException("file == null");
    return source(new FileInputStream(file));
  }

  /** Returns a sink that writes to {@code file}. */
  public static OkioSink sink(File file) throws FileNotFoundException {
    if (file == null) throw new IllegalArgumentException("file == null");
    return sink(new FileOutputStream(file));
  }

  /** Returns a sink that appends to {@code file}. */
  public static OkioSink appendingSink(File file) throws FileNotFoundException {
    if (file == null) throw new IllegalArgumentException("file == null");
    return sink(new FileOutputStream(file, true));
  }

  /** Returns a sink that writes nowhere. */
  public static OkioSink blackhole() {
    return new OkioSink() {
      @Override public void write(OkioBuffer source, long byteCount) throws IOException {
        source.skip(byteCount);
      }

      @Override public void flush() throws IOException {
      }

      @Override public OkioTimeout timeout() {
        return OkioTimeout.NONE;
      }

      @Override public void close() throws IOException {
      }
    };
  }

  /**
   * Returns a source that reads from {@code socket}. Prefer this over {@link
   * #source(InputStream)} because this method honors timeouts. When the socket
   * read times out, the socket is asynchronously closed by a watchdog thread.
   */
  public static OkioSource source(Socket socket) throws IOException {
    if (socket == null) throw new IllegalArgumentException("socket == null");
    if (socket.getInputStream() == null) throw new IOException("socket's input stream == null");
    OkioAsyncTimeout timeout = timeout(socket);
    OkioSource source = source(socket.getInputStream(), timeout);
    return timeout.source(source);
  }

  private static OkioAsyncTimeout timeout(final Socket socket) {
    return new OkioAsyncTimeout() {
      @Override protected IOException newTimeoutException(IOException cause) {
        InterruptedIOException ioe = new SocketTimeoutException("timeout");
        if (cause != null) {
          ioe.initCause(cause);
        }
        return ioe;
      }

      @Override protected void timedOut() {
        try {
          socket.close();
        } catch (Exception e) {
          logger.log(Level.WARNING, "Failed to close timed out socket " + socket, e);
        } catch (AssertionError e) {
          if (isAndroidGetsocknameError(e)) {
            // Catch this exception due to a Firmware issue up to android 4.2.2
            // https://code.google.com/p/android/issues/detail?id=54072
            logger.log(Level.WARNING, "Failed to close timed out socket " + socket, e);
          } else {
            throw e;
          }
        }
      }
    };
  }

  /**
   * Returns true if {@code e} is due to a firmware bug fixed after Android 4.2.2.
   * https://code.google.com/p/android/issues/detail?id=54072
   */
  static boolean isAndroidGetsocknameError(AssertionError e) {
    return e.getCause() != null && e.getMessage() != null
        && e.getMessage().contains("getsockname failed");
  }
}
