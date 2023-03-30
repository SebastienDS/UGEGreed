package fr.uge.greed;

import java.nio.ByteBuffer;
import java.util.Objects;

public record Header(TransmissionMode mode, byte opcode) {
  public Header {
    Objects.requireNonNull(mode);
  }

  public int getRequiredBytes() {
    return 2 * Byte.BYTES + 2 * mode.getRequiredBytes();
  }

  public void encode(ByteBuffer buffer) {
    Objects.requireNonNull(buffer);
    switch (mode) {
      case TransmissionMode.Local ignored -> buffer.put((byte)0).put(opcode);
      case TransmissionMode.Broadcast broadcast -> {
        buffer.put((byte)1).put(opcode);
        broadcast.source().encode(buffer);
      }
      case TransmissionMode.Transfer transfer -> {
        buffer.put((byte) 2).put(opcode);
        transfer.source().encode(buffer);
        transfer.destination().encode(buffer);
      }
    }
  }
}

