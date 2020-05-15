package io.cloudstate.javasupport.crudtwo;

@CrudEntity
public class CrudEntityExample {

  @CommandHandler
  public com.google.protobuf.Empty createCommand(SomeCrudCommandType command, CommandContext ctx) {
    SomeState state = new SomeState(ctx.state());
    state.set("YourSate");

    ctx.emit(state);
    return com.google.protobuf.Empty.getDefaultInstance();
  }

  @CommandHandler
  public SomeState fetchCommand(SomeCrudCommandType command, CommandContext ctx) {
    return new SomeState(ctx.state());
  }

  public static class SomeCrudCommandType {
    // with entityId
    // with subEntityId
    // with crudCommandType
  }

  public static class SomeState {
    private Object state;

    public SomeState(Object state) {
      this.state = state;
    }

    public void set(String yourState) {
      this.state = yourState;
    }
  }
}
