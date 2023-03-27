package fr.uge.greed;

import java.util.Objects;

public sealed interface TransmissionMode {
  record Local() implements TransmissionMode {}

  record Transfer(SocketAddress source, SocketAddress destination) implements TransmissionMode {
    public Transfer {
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