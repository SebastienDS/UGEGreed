package fr.uge.greed.packet;

import fr.uge.greed.Payload;
import fr.uge.greed.SocketAddress;

import java.util.Objects;


public record Disconnection(SocketAddress address) implements Payload {
  public Disconnection {
    Objects.requireNonNull(address);
  }
}
