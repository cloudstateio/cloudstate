/**
 * CRUD support.
 *
 * <p>CRUD entities can be annotated with the {@link
 * io.cloudstate.javasupport.crud.CrudEntity @CrudEntity} annotation, and supply command handlers
 * using the {@link io.cloudstate.javasupport.crud.CommandHandler @CommandHandler} annotation.
 *
 * <p>In addition, {@link io.cloudstate.javasupport.crud.StateHandler @StateHandler} annotated
 * methods should be defined to handle entity state.
 */
package io.cloudstate.javasupport.crud;
