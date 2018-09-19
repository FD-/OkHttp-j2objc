/*
 * Copyright (C) 2015 Square, Inc.
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
import java.util.concurrent.TimeUnit;

/** A {@link OkioTimeout} which forwards calls to another. Useful for subclassing. */
public class OkioForwardingTimeout extends OkioTimeout {
  private OkioTimeout delegate;

  public OkioForwardingTimeout(OkioTimeout delegate) {
    if (delegate == null) throw new IllegalArgumentException("delegate == null");
    this.delegate = delegate;
  }

  /** {@link OkioTimeout} instance to which this instance is currently delegating. */
  public final OkioTimeout delegate() {
    return delegate;
  }

  public final OkioForwardingTimeout setDelegate(OkioTimeout delegate) {
    if (delegate == null) throw new IllegalArgumentException("delegate == null");
    this.delegate = delegate;
    return this;
  }

  @Override public OkioTimeout timeout(long timeout, TimeUnit unit) {
    return delegate.timeout(timeout, unit);
  }

  @Override public long timeoutNanos() {
    return delegate.timeoutNanos();
  }

  @Override public boolean hasDeadline() {
    return delegate.hasDeadline();
  }

  @Override public long deadlineNanoTime() {
    return delegate.deadlineNanoTime();
  }

  @Override public OkioTimeout deadlineNanoTime(long deadlineNanoTime) {
    return delegate.deadlineNanoTime(deadlineNanoTime);
  }

  @Override public OkioTimeout clearTimeout() {
    return delegate.clearTimeout();
  }

  @Override public OkioTimeout clearDeadline() {
    return delegate.clearDeadline();
  }

  @Override public void throwIfReached() throws IOException {
    delegate.throwIfReached();
  }
}
