package fr.uge.greed.packet;

import fr.uge.greed.Payload;

import java.nio.ByteBuffer;
import java.util.Objects;

public record ResponseState(int tasksInProgress) implements Payload {
  public ResponseState {
    if (tasksInProgress < 0) {
      throw new IllegalArgumentException("TasksInProgress must be > 0");
    }
  }

  @Override
  public int getRequiredBytes() {
    return Integer.BYTES;
  }

  @Override
  public void encode(ByteBuffer buffer) {
    Objects.requireNonNull(buffer);
    buffer.putInt(tasksInProgress);
  }
}
