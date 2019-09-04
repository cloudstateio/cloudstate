/**
 * Conflict-free Replicated Data Type support.
 * <p>
 * CRDT entities can be annotated with the {@link io.cloudstate.javasupport.crdt.CrdtEntity @CrdtEntity} annotation,
 * and supply command handlers using the {@link io.cloudstate.javasupport.crdt.CommandHandler @CommandHandler}
 * annotation.
 * <p>
 * The data stored by a CRDT entity can be stored in a subtype of {@link io.cloudstate.javasupport.crdt.Crdt}. These
 * can be created using a {@link io.cloudstate.javasupport.crdt.CrdtFactory}, which is a super-interface of both the
 * {@link io.cloudstate.javasupport.crdt.CrdtCreationContext}, available for injection constructors, and of the
 * {@link io.cloudstate.javasupport.crdt.CommandContext}, available for injection in {@code @CommandHandler} annotated
 * methods.
 */
package io.cloudstate.javasupport.crdt;