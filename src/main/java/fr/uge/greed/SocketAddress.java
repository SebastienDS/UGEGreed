package fr.uge.greed;

import java.util.Objects;

public record SocketAddress(String addressIP, int port) {
  public SocketAddress {
    Objects.requireNonNull(addressIP);
  }
}
