package fr.uge.greed;

import java.util.Optional;

public record ResponseTask(long taskId, Byte taskStatus, Optional<String> response) {
  public ResponseTask {
    if (taskStatus < 0 || taskStatus >= 4)
      throw new IllegalArgumentException("Task status may be between 0 and 4.");
  }
}
