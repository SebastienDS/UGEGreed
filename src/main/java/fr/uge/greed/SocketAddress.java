package fr.uge.greed;

import java.nio.ByteBuffer;
import java.util.Objects;

public record SocketAddress(String addressIP, int port) {
  public SocketAddress {
    Objects.requireNonNull(addressIP);
  }

  public int getRequiredBytes() {
    return Integer.BYTES + 16 * Byte.BYTES; //TODO
  }

  public void encode(ByteBuffer buffer) {
    //TODO
  }
}
