package fr.uge.greed.packet;

import fr.uge.greed.Payload;

public record ResponseState(int taskInProgress) implements Payload {
  public ResponseState {
    if (taskInProgress < 0) {
      throw new IllegalArgumentException("TaskInProgress must be > 0");
    }
  }
}
