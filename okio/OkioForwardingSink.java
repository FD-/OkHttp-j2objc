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

import java.io.IOException;

/** A {@link OkioSink} which forwards calls to another. Useful for subclassing. */
public abstract class OkioForwardingSink implements OkioSink {
  private final OkioSink delegate;

  public OkioForwardingSink(OkioSink delegate) {
    if (delegate == null) throw new IllegalArgumentException("delegate == null");
    this.delegate = delegate;
  }

  /** {@link OkioSink} to which this instance is delegating. */
  public final OkioSink delegate() {
    return delegate;
  }

  @Override public void write(OkioBuffer source, long byteCount) throws IOException {
    delegate.write(source, byteCount);
  }

  @Override public void flush() throws IOException {
    delegate.flush();
  }

  @Override public OkioTimeout timeout() {
    return delegate.timeout();
  }

  @Override public void close() throws IOException {
    delegate.close();
  }

  @Override public String toString() {
    return getClass().getSimpleName() + "(" + delegate.toString() + ")";
  }
}
