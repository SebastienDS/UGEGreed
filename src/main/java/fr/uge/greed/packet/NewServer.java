package fr.uge.greed.packet;

import fr.uge.greed.Payload;
import fr.uge.greed.SocketAddress;

import java.util.Objects;

public record NewServer(SocketAddress address) implements Payload {
  public NewServer {
    Objects.requireNonNull(address);
  }
}
