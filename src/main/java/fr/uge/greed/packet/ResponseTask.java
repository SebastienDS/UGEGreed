package fr.uge.greed.packet;

import fr.uge.greed.Payload;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

public record ResponseTask(long taskId, byte taskStatus, Optional<String> response) implements Payload {
  public static final byte OPCODE = 8;

  public ResponseTask {
    if (taskStatus < 0 || taskStatus >= 4)
      throw new IllegalArgumentException("Task status may be between 0 and 3.");
    Objects.requireNonNull(response);
  }

  @Override
  public int getRequiredBytes() {
    return Long.BYTES + Byte.BYTES + (response.isEmpty() ? 0 : Integer.BYTES + 1024);
  }

  @Override
  public void encode(ByteBuffer buffer) {
    Objects.requireNonNull(buffer);
    buffer.putLong(taskId).put(taskStatus);
    if (response.isPresent()) {
      var encodedResponse = StandardCharsets.UTF_8.encode(response.get());
      buffer.putInt(encodedResponse.remaining());
      buffer.put(encodedResponse);
    }

  }
}
