package fr.uge.greed.packet;

import fr.uge.greed.Payload;
import fr.uge.greed.SocketAddress;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

public record Reconnection(String authentication, SocketAddress address, List<SocketAddress> addresses) implements Payload {
  public static final byte OPCODE = 11;

  public Reconnection {
    Objects.requireNonNull(address);
    Objects.requireNonNull(addresses);
    addresses = List.copyOf(addresses);
  }
  @Override
  public int getRequiredBytes() {
    return Integer.BYTES + 1024 + address.getRequiredBytes() + Integer.BYTES + addresses.stream().mapToInt(SocketAddress::getRequiredBytes).sum();
  }

  @Override
  public void encode(ByteBuffer buffer) {
    Objects.requireNonNull(buffer);
    var encodedAuth = StandardCharsets.UTF_8.encode(authentication);
    buffer.putInt(encodedAuth.remaining())
        .put(encodedAuth);
    address.encode(buffer);
    buffer.putInt(addresses.size());
    addresses.forEach(address -> address.encode(buffer));
  }

}
