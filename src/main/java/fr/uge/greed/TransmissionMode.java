package fr.uge.greed;

import java.util.Objects;

public sealed interface TransmissionMode {
  int getRequiredBytes();

  record Local() implements TransmissionMode {
    @Override
    public int getRequiredBytes() {
      return Byte.BYTES;
    }
  }

  record Transfer(SocketAddress source, SocketAddress destination) implements TransmissionMode {
    public Transfer {
      Objects.requireNonNull(source);
      Objects.requireNonNull(destination);
    }

    @Override
    public int getRequiredBytes() {
      return Byte.BYTES + source.getRequiredBytes() + destination.getRequiredBytes();
    }
  }

  record Broadcast(SocketAddress source) implements TransmissionMode {
    public Broadcast {
      Objects.requireNonNull(source);
    }

    @Override
    public int getRequiredBytes() {
      return Byte.BYTES + source.getRequiredBytes();
    }
  }
}