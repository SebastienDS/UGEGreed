package fr.uge.greed.packet;

import fr.uge.greed.Payload;

import java.nio.ByteBuffer;
import java.util.Objects;

public record RejectTask(long id) implements Payload {
  public static final byte OPCODE = 7;

  @Override
  public int getRequiredBytes() {
    return Long.BYTES;
  }

  @Override
  public void encode(ByteBuffer buffer) {
    Objects.requireNonNull(buffer);
    buffer.putLong(id);
  }
}
