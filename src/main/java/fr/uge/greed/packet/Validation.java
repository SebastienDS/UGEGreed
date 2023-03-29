package fr.uge.greed.packet;

import fr.uge.greed.Payload;
import fr.uge.greed.SocketAddress;

import java.util.List;
import java.util.Objects;

public record Validation(List<SocketAddress> addresses) implements Payload {
  public Validation {
    Objects.requireNonNull(addresses);
    addresses = List.copyOf(addresses);
  }
}