package fr.uge.greed;

import java.util.Objects;

public sealed interface TransmissionMode {
  record Local() implements TransmissionMode {}

  record Transfert(SocketAddress source, SocketAddress destination) implements TransmissionMode {
    public Transfert {
      Objects.requireNonNull(source);
      Objects.requireNonNull(destination);
    }
  }

  record Broadcast(SocketAddress source) implements TransmissionMode {
    public Broadcast {
      Objects.requireNonNull(source);
    }
  }
}