package fr.uge.greed.reader;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.StringJoiner;

public class IPAddressReader implements Reader<String> {
  private static final int BUFFER_SIZE = Byte.BYTES + 16 * Byte.BYTES;

  private enum State {
    DONE, WAITING_FORMAT, WAITING_BYTES, ERROR
  };

  private State state = State.WAITING_FORMAT;
  private final ByteBuffer internalBuffer = ByteBuffer.allocate(BUFFER_SIZE); // write-mode
  private final ByteReader byteReader = new ByteReader();
  private String value;

  @Override
  public ProcessStatus process(ByteBuffer buffer) {
    Objects.requireNonNull(buffer);
    if (state == State.DONE || state == State.ERROR) {
      throw new IllegalStateException();
    }
    if (state == State.WAITING_FORMAT) {
      processFormat(buffer);
      if (state == State.ERROR) {
        reset();
        return ProcessStatus.ERROR;
      }
    }
    if (state != State.WAITING_BYTES) {
      return ProcessStatus.REFILL;
    }
    buffer.flip();
    try {
      if (buffer.remaining() <= internalBuffer.remaining()) {
        internalBuffer.put(buffer);
      } else {
        var oldLimit = buffer.limit();
        buffer.limit(internalBuffer.remaining());
        internalBuffer.put(buffer);
        buffer.limit(oldLimit);
      }
    } finally {
      buffer.compact();
    }
    if (internalBuffer.hasRemaining()) {
      return ProcessStatus.REFILL;
    }
    state = State.DONE;
    internalBuffer.flip();

    StringJoiner stringJoiner;
    if (internalBuffer.limit() == 4) {
      stringJoiner = new StringJoiner(".");
      for (var i = 0; i < 4; i++) {
        stringJoiner.add("" + (internalBuffer.get() & 0xff));
      }
    } else {
      stringJoiner = new StringJoiner(":");
      for (var i = 0; i < 16; i += 2) {
        stringJoiner.add(String.format("%X%X", internalBuffer.get(), internalBuffer.get()));
      }
    }
    value = stringJoiner.toString();

    return ProcessStatus.DONE;
  }

  private void processFormat(ByteBuffer buffer) {
    var status = byteReader.process(buffer);
    switch (status) {
      case DONE -> {
        var format = byteReader.get();
        if (format == 4) {
          internalBuffer.limit(4);
        } else if (format == 6) {
          internalBuffer.limit(16);
        } else {
          state = State.ERROR;
          return;
        }
        byteReader.reset();
        state = State.WAITING_BYTES;
      }
      case ERROR -> state = State.ERROR;
    }
  }

  @Override
  public String get() {
    if (state != State.DONE) {
      throw new IllegalStateException();
    }
    return value;
  }

  @Override
  public void reset() {
    state = State.WAITING_FORMAT;
    internalBuffer.clear();
    byteReader.reset();
  }
}