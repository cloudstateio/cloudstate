// FIXME THIS FILE IS FOR PROOF-OF-CONCEPT ONLY
// THESE SUBSTITUTIONS SHOULD BE PRESENT IN THE SHADED VERSION OF NETTY IN GRPC-NETTY-SHADED,
// AGRONA, AND IN PROTOBUF_JAVA. NOT HERE!

package io.cloudstate.internal.svm;

import com.oracle.svm.core.annotate.*;

@TargetClass(
    className =
        "io.grpc.netty.shaded.io.netty.util.internal.shaded.org.jctools.util.UnsafeRefArrayAccess")
final
class Target_io_grpc_netty_shaded_io_netty_util_internal_shaded_org_jctools_util_UnsafeRefArrayAccess {
  @Alias
  @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.ArrayIndexShift, declClass = Object[].class)
  public static int REF_ELEMENT_SHIFT;
}

@TargetClass(className = "io.grpc.netty.shaded.io.netty.util.internal.PlatformDependent")
final class Target_io_grpc_netty_shaded_io_netty_util_internal_PlatformDependent {
  @Alias
  @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.ArrayBaseOffset, declClass = byte[].class)
  private static long BYTE_ARRAY_BASE_OFFSET;
}

@TargetClass(className = "io.grpc.netty.shaded.io.netty.util.internal.PlatformDependent0")
final class Target_io_grpc_netty_shaded_io_netty_util_internal_PlatformDependent0 {
  @Alias
  @RecomputeFieldValue(
      kind = RecomputeFieldValue.Kind.FieldOffset,
      declClassName = "java.nio.Buffer",
      name = "address")
  private static long ADDRESS_FIELD_OFFSET;
}

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
