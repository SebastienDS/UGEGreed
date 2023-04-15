package fr.uge.greed.packet;

import fr.uge.greed.Payload;
import fr.uge.greed.SocketAddress;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

public record Connection(String authentication, SocketAddress address) implements Payload {
  public static final byte OPCODE = 0;

  public Connection {
    Objects.requireNonNull(address);
    Objects.requireNonNull(authentication);
  }

  @Override
  public int getRequiredBytes() {
    return Integer.BYTES + 1024 + address.getRequiredBytes();
  }

  @Override
  public void encode(ByteBuffer buffer) {
    Objects.requireNonNull(buffer);
    var encodedAuth = StandardCharsets.UTF_8.encode(authentication);
    buffer.putInt(encodedAuth.remaining())
        .put(encodedAuth);
    address.encode(buffer);
  }
}
