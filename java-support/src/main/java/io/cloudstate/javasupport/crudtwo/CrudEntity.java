package io.cloudstate.javasupport.crudtwo;

import io.cloudstate.javasupport.impl.CloudStateAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** An crud entity. */
@CloudStateAnnotation
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CrudEntity {
  /**
   * The name of the persistence id.
   *
   * <p>If not specified, defaults to the entities unqualified classname. It's strongly recommended
   * that you specify it explicitly.
   */
  String persistenceId() default "";

  /**
   * Specifies how snapshots of the entity state should be made: Zero means use default from
   * configuration file. (Default) Any negative value means never snapshot. Any positive value means
   * snapshot at-or-after that number of events.
   */
  int snapshotEvery() default 0;
}
