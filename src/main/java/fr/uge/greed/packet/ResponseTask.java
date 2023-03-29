package fr.uge.greed.packet;

import fr.uge.greed.Payload;

import java.util.Objects;
import java.util.Optional;

public record ResponseTask(long taskId, byte taskStatus, Optional<String> response) implements Payload {
  public ResponseTask {
    if (taskStatus < 0 || taskStatus >= 4)
      throw new IllegalArgumentException("Task status may be between 0 and 3.");
    Objects.requireNonNull(response);
  }
}
