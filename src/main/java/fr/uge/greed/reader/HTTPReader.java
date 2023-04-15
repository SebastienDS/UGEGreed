package fr.uge.greed.reader;

import fr.uge.greed.HTTPException;
import fr.uge.greed.packet.http.HTTPHeader;
import fr.uge.greed.packet.http.HTTPResponse;

import java.nio.ByteBuffer;
import java.util.*;

public class HTTPReader implements Reader<HTTPResponse> {
  private enum State {
    DONE, WAITING_HEADER, WAITING_BODY, ERROR
  };

  private State state = State.WAITING_HEADER;
  private ByteBuffer internalBuffer = ByteBuffer.allocate(0);
  private final HTTPLineReader httpLineReader = new HTTPLineReader();
  private final ArrayList<String> headerLines = new ArrayList<>();
  private HTTPHeader header;
  private byte[] body;

  @Override
  public ProcessStatus process(ByteBuffer buffer) {
    Objects.requireNonNull(buffer);
    try {
      return process_(buffer);
    } catch (HTTPException e) {
      state = State.ERROR;
      return ProcessStatus.ERROR;
    }
  }

  private ProcessStatus process_(ByteBuffer buffer) throws HTTPException {
    if (state == State.DONE || state == State.ERROR) {
      throw new IllegalStateException();
    }
    if (state == State.WAITING_HEADER) {
      processHeader(buffer);
      if (state == State.ERROR) {
        reset();
        return ProcessStatus.ERROR;
      }
      if (state == State.WAITING_HEADER) {
        return ProcessStatus.REFILL;
      }

      var contentLength = header.getContentLength();
      if (internalBuffer.capacity() < contentLength) {
        internalBuffer = ByteBuffer.allocate(contentLength);
      } else {
        internalBuffer.clear();
        internalBuffer.limit(contentLength);
      }
    }

    processBody(buffer);
    if (state == State.ERROR) {
      reset();
      return ProcessStatus.ERROR;
    }
    if (internalBuffer.hasRemaining()) {
      return ProcessStatus.REFILL;
    }

    body = internalBuffer.array();

    state = State.DONE;
    return ProcessStatus.DONE;
  }

  private void processHeader(ByteBuffer buffer) throws HTTPException {
    for (;;) {
      if (!processLine(buffer)) return;
    }
  }

  private boolean processLine(ByteBuffer buffer) throws HTTPException {
    var status = httpLineReader.process(buffer);
    switch (status) {
      case DONE -> {
        var line = httpLineReader.get();
        httpLineReader.reset();
        if (line.isEmpty()) {
          state = State.WAITING_BODY;
          processHeaderContent();
          return false;
        }
        headerLines.add(line);
      }
      case ERROR -> {
        state = State.ERROR;
        return false;
      }
      case REFILL -> {
        return false;
      }
    }
    return true;
  }

  private void processHeaderContent() throws HTTPException {
    var fields = new HashMap<String, String>();
    headerLines.stream()
        .skip(1)
        .forEach(line -> {
          var entry = line.split(":", 2);
          fields.merge(entry[0], entry[1], (a, b) -> a + ";" + b);
        });
    header = HTTPHeader.create(headerLines.get(0), fields);
  }

  private void processBody(ByteBuffer buffer) {
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
  }

  @Override
  public HTTPResponse get() {
    if (state != State.DONE) {
      throw new IllegalStateException();
    }
    var buffer = ByteBuffer.allocate(internalBuffer.limit())
        .put(internalBuffer.flip());
    return new HTTPResponse(header, buffer);
  }

  @Override
  public void reset() {
    state = State.WAITING_HEADER;
    httpLineReader.reset();
    headerLines.clear();
    internalBuffer.clear();
  }
}