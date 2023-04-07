package fr.uge.greed.packet;

import fr.uge.greed.Payload;
import fr.uge.greed.SocketAddress;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;

public record Reconnection(List<SocketAddress> addresses) implements Payload {
  public static final byte OPCODE = 11;

  public Reconnection {
    Objects.requireNonNull(addresses);
    addresses = List.copyOf(addresses);
  }
  @Override
  public int getRequiredBytes() {
    return Integer.BYTES + addresses.stream().mapToInt(SocketAddress::getRequiredBytes).sum();
  }

  @Override
  public void encode(ByteBuffer buffer) {
    Objects.requireNonNull(buffer);
    buffer.putInt(addresses.size());
    addresses.forEach(address -> address.encode(buffer));
  }

}
