package fr.uge.greed;


import java.nio.ByteBuffer;
import java.util.Objects;

public record Packet(Header header, Payload payload) {
  public Packet {
    Objects.requireNonNull(header);
    Objects.requireNonNull(payload);
  }

  public ByteBuffer toByteBuffer() {
    var buffer = ByteBuffer.allocate(header.getRequiredBytes() + payload.getRequiredBytes());
    header.encode(buffer);
    payload.encode(buffer);
    return buffer;
  }
}
