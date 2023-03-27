package fr.uge.greed;

import java.util.Objects;

public record Header(TransmissionMode mode, byte opcode) {
  public Header {
    Objects.requireNonNull(mode);
  }
}

