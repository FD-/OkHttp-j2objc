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



/**
 * A collection of unused segments, necessary to avoid GC churn and zero-fill.
 * This pool is a thread-safe static singleton.
 */
final class OkioSegmentPool {
  /** The maximum number of bytes to pool. */
  // TODO: Is 64 KiB a good maximum size? Do we ever have that many idle segments?
  static final long MAX_SIZE = 64 * 1024; // 64 KiB.

  /** Singly-linked list of segments. */
  static
  OkioSegment next;

  /** Total bytes in this pool. */
  static long byteCount;

  private OkioSegmentPool() {
  }

  static OkioSegment take() {
    synchronized (OkioSegmentPool.class) {
      if (next != null) {
        OkioSegment result = next;
        next = result.next;
        result.next = null;
        byteCount -= OkioSegment.SIZE;
        return result;
      }
    }
    return new OkioSegment(); // Pool is empty. Don't zero-fill while holding a lock.
  }

  static void recycle(OkioSegment segment) {
    if (segment.next != null || segment.prev != null) throw new IllegalArgumentException();
    if (segment.shared) return; // This segment cannot be recycled.
    synchronized (OkioSegmentPool.class) {
      if (byteCount + OkioSegment.SIZE > MAX_SIZE) return; // Pool is full.
      byteCount += OkioSegment.SIZE;
      segment.next = next;
      segment.pos = segment.limit = 0;
      next = segment;
    }
  }
}
