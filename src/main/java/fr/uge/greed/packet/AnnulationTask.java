package fr.uge.greed.packet;

import fr.uge.greed.Payload;

import java.nio.ByteBuffer;
import java.util.Objects;

public record AnnulationTask(long id, byte status, long startRemainingValues) implements Payload {
  public static final byte OPCODE = 9;
  public static final byte CANCEL_MY_TASK = 0;
  public static final byte CANCEL_ASSIGNED_TASK = 1;

  @Override
  public int getRequiredBytes() {
    return 2 * Long.BYTES;
  }

  @Override
  public void encode(ByteBuffer buffer) {
    Objects.requireNonNull(buffer);
    buffer.putLong(id).putLong(startRemainingValues);
  }

}
