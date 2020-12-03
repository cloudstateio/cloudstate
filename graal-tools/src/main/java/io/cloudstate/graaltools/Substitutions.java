/*
 * Copyright 2019 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// FIXME THIS FILE IS FOR PROOF-OF-CONCEPT ONLY
// THESE SUBSTITUTIONS SHOULD BE PRESENT IN NETTY, IN GRPC-NETTY-SHADED, AGRONA, AND IN
// PROTOBUF_JAVA. NOT HERE!

package io.cloudstate.graaltools;

import com.oracle.svm.core.annotate.*;

@TargetClass(className = "org.agrona.concurrent.AbstractConcurrentArrayQueue")
final class Target_org_agrona_concurrent_AbstractConcurrentArrayQueue {
  @Alias
  @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.ArrayIndexShift, declClass = Object[].class)
  public static int SHIFT_FOR_SCALE;
}

@TargetClass(className = "com.google.protobuf.UnsafeUtil")
final class Target_com_google_protobuf_UnsafeUtil {
  @Alias
  @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.ArrayBaseOffset, declClass = boolean[].class)
  public static long BOOLEAN_ARRAY_BASE_OFFSET;

  @Alias
  @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.ArrayIndexScale, declClass = boolean[].class)
  public static long BOOLEAN_ARRAY_INDEX_SCALE;

  @Alias
  @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.ArrayBaseOffset, declClass = int[].class)
  public static long INT_ARRAY_BASE_OFFSET;

  @Alias
  @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.ArrayIndexScale, declClass = int[].class)
  public static long INT_ARRAY_INDEX_SCALE;

  @Alias
  @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.ArrayBaseOffset, declClass = long[].class)
  public static long LONG_ARRAY_BASE_OFFSET;

  @Alias
  @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.ArrayIndexScale, declClass = long[].class)
  public static long LONG_ARRAY_INDEX_SCALE;

  @Alias
  @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.ArrayBaseOffset, declClass = float[].class)
  public static long FLOAT_ARRAY_BASE_OFFSET;

  @Alias
  @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.ArrayIndexScale, declClass = float[].class)
  public static long FLOAT_ARRAY_INDEX_SCALE;

  @Alias
  @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.ArrayBaseOffset, declClass = double[].class)
  public static long DOUBLE_ARRAY_BASE_OFFSET;

  @Alias
  @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.ArrayIndexScale, declClass = double[].class)
  public static long DOUBLE_ARRAY_INDEX_SCALE;

  @Alias
  @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.ArrayBaseOffset, declClass = Object[].class)
  public static long OBJECT_ARRAY_BASE_OFFSET;

  @Alias
  @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.ArrayIndexScale, declClass = Object[].class)
  public static long OBJECT_ARRAY_INDEX_SCALE;
}
