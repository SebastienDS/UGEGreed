package fr.uge.greed.packet;

import fr.uge.greed.Payload;

import java.nio.ByteBuffer;
import java.util.Objects;

public record AnnulationTask(long id, long startRemainingValues) implements Payload {
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
