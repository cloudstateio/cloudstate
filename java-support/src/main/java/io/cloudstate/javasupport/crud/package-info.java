/**
 * CRUD support.
 *
 * <p>CRUD entities can be annotated with the {@link
 * io.cloudstate.javasupport.crud.CrudEntity @CrudEntity} annotation, and supply command handlers
 * using the {@link io.cloudstate.javasupport.crud.CommandHandler @CommandHandler} annotation.
 *
 * <p>In addition, {@link io.cloudstate.javasupport.crud.UpdateStateHandler @UpdateStateHandler} and
 * {@link io.cloudstate.javasupport.crud.DeleteStateHandler @DeleteStateHandler} annotated methods
 * should be defined to handle entity state respectively.
 */
package io.cloudstate.javasupport.crud;
