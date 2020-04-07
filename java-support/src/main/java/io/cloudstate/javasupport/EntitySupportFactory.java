package io.cloudstate.javasupport;

/** Auxiliar interface for support DI environments */
public interface EntitySupportFactory {
  /**
   * The reference to the Entity.
   *
   * @return The reference to the Entity.
   */
  Object create(Context context, String entity);

  /**
   * Return Class of Entity.
   *
   * @return The Class of Entity.
   */
  Class<?> typeClass();
}