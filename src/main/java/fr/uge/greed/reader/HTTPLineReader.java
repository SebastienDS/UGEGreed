package fr.uge.greed.reader;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class HTTPLineReader implements Reader<String> {
  private enum State {
    DONE, WAITING, ERROR
  };

  private static final int BUFFER_SIZE = 1024;

  private State state = State.WAITING;
  private ByteBuffer internalBuffer = ByteBuffer.allocate(BUFFER_SIZE);
  private byte previous;
  private byte current;
  private String line;


  @Override
  public ProcessStatus process(ByteBuffer buffer) {
    Objects.requireNonNull(buffer);
    if (state == State.DONE || state == State.ERROR) {
      throw new IllegalStateException();
    }

    buffer.flip();
    while (buffer.hasRemaining()) {
      if (!internalBuffer.hasRemaining()) { // resize
        var newBuffer = ByteBuffer.allocate(internalBuffer.capacity() * 2);
        newBuffer.put(internalBuffer);
        internalBuffer = newBuffer;
      }
      current = buffer.get();
      internalBuffer.put(current);
      if (previous == '\r' && current == '\n') {
        internalBuffer.flip();
        line = StandardCharsets.US_ASCII.decode(internalBuffer).toString().trim();
        state = State.DONE;
        break;
      }
      previous = current;
    }
    buffer.compact();

    if (state == State.WAITING) {
      return ProcessStatus.REFILL;
    }
    state = State.DONE;
    return ProcessStatus.DONE;
  }

  @Override
  public String get() {
    if (state != State.DONE) {
      throw new IllegalStateException();
    }
    return line;
  }

  @Override
  public void reset() {
    state = State.WAITING;
    internalBuffer.clear();
    previous = 0;
    current = 0;
  }
}