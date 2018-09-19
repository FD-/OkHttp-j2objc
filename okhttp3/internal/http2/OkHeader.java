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
package okhttp3.internal.http2;

import okhttp3.internal.OkUtil;
import okio.OkioByteString;

/** HTTP header: the name is an ASCII string, but the value can be UTF-8. */
public final class OkHeader {
  // Special header names defined in HTTP/2 spec.
  public static final OkioByteString PSEUDO_PREFIX = OkioByteString.encodeUtf8(":");
  public static final OkioByteString RESPONSE_STATUS = OkioByteString.encodeUtf8(":status");
  public static final OkioByteString TARGET_METHOD = OkioByteString.encodeUtf8(":method");
  public static final OkioByteString TARGET_PATH = OkioByteString.encodeUtf8(":path");
  public static final OkioByteString TARGET_SCHEME = OkioByteString.encodeUtf8(":scheme");
  public static final OkioByteString TARGET_AUTHORITY = OkioByteString.encodeUtf8(":authority");

  /** Name in case-insensitive ASCII encoding. */
  public final OkioByteString name;
  /** Value in UTF-8 encoding. */
  public final OkioByteString value;
  final int hpackSize;

  // TODO: search for toLowerCase and consider moving logic here.
  public OkHeader(String name, String value) {
    this(OkioByteString.encodeUtf8(name), OkioByteString.encodeUtf8(value));
  }

  public OkHeader(OkioByteString name, String value) {
    this(name, OkioByteString.encodeUtf8(value));
  }

  public OkHeader(OkioByteString name, OkioByteString value) {
    this.name = name;
    this.value = value;
    this.hpackSize = 32 + name.size() + value.size();
  }

  @Override public boolean equals(Object other) {
    if (other instanceof OkHeader) {
      OkHeader that = (OkHeader) other;
      return this.name.equals(that.name)
          && this.value.equals(that.value);
    }
    return false;
  }

  @Override public int hashCode() {
    int result = 17;
    result = 31 * result + name.hashCode();
    result = 31 * result + value.hashCode();
    return result;
  }

  @Override public String toString() {
    return OkUtil.format("%s: %s", name.utf8(), value.utf8());
  }
}
