package fr.uge.greed.packet;

import fr.uge.greed.Payload;

import java.nio.ByteBuffer;
import java.util.Objects;

public record RequestState() implements Payload {
  public static final byte OPCODE = 4;

  @Override
  public int getRequiredBytes() {
    return 0;
  }

  @Override
  public void encode(ByteBuffer buffer) {
    Objects.requireNonNull(buffer);
  }
}
