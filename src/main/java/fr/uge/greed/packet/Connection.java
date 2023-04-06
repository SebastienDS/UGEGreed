package fr.uge.greed.packet;

import fr.uge.greed.Payload;
import fr.uge.greed.SocketAddress;

import java.nio.ByteBuffer;
import java.util.Objects;

public record Connection(SocketAddress address) implements Payload {
  public Connection {
    Objects.requireNonNull(address);
  }

  @Override
  public int getRequiredBytes() {
    return address.getRequiredBytes();
  }

  @Override
  public void encode(ByteBuffer buffer) {
    Objects.requireNonNull(buffer);
    address.encode(buffer);
  }
}
