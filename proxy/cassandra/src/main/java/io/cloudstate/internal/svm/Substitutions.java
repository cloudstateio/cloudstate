// FIXME THIS FILE IS FOR PROOF-OF-CONCEPT ONLY
// THESE SUBSTITUTIONS SHOULD BE PRESENT IN THE SHADED VERSION OF NETTY IN Cassandra client. NOT
// HERE!

package io.cloudstate.internal.svm;

import com.oracle.svm.core.annotate.*;

// For Netty 4

@TargetClass(className = "io.netty.util.internal.shaded.org.jctools.util.UnsafeRefArrayAccess")
final class Target_io_netty_util_internal_shaded_org_jctools_util_UnsafeRefArrayAccess {
  @Alias
  @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.ArrayIndexShift, declClass = Object[].class)
  public static int REF_ELEMENT_SHIFT;
}

@TargetClass(className = "io.netty.util.internal.PlatformDependent")
final class Target_io_netty_util_internal_PlatformDependent {
  @Alias
  @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.ArrayBaseOffset, declClass = byte[].class)
  private static long ARRAY_BASE_OFFSET;
}

@TargetClass(className = "io.netty.util.internal.PlatformDependent0")
final class Target_io_netty_util_internal_PlatformDependent0 {
  @Alias
  @RecomputeFieldValue(
      kind = RecomputeFieldValue.Kind.FieldOffset,
      declClassName = "java.nio.Buffer",
      name = "address")
  private static long ADDRESS_FIELD_OFFSET;
}
