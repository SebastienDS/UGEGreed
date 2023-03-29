package fr.uge.greed.packet;

import fr.uge.greed.Payload;
import fr.uge.greed.SocketAddress;

import java.util.Objects;

public record RequestState(SocketAddress source) implements Payload {
  public RequestState {
    Objects.requireNonNull(source);
  }
}
