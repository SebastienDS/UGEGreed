package fr.uge.greed.packet;

import fr.uge.greed.Payload;
import fr.uge.greed.SocketAddress;

import java.nio.ByteBuffer;
import java.util.Objects;

public record RequestState(SocketAddress source) implements Payload {
  public RequestState {
    Objects.requireNonNull(source);
  }

  @Override
  public int getRequiredBytes() {
    return source.getRequiredBytes();
  }

  @Override
  public void encode(ByteBuffer buffer) {
    Objects.requireNonNull(buffer);
    source.encode(buffer);
  }
}
