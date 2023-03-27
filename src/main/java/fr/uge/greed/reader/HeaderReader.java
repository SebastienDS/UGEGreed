package fr.uge.greed.reader;

import fr.uge.greed.Header;
import fr.uge.greed.SocketAddress;
import fr.uge.greed.TransmissionMode;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Objects;

public class HeaderReader implements Reader<Header> {
  private static final int MODE_COUNT = 3;
  private static final int OPCODE_COUNT = 12;

  private enum State {
    DONE, WAITING_MODE, WAITING_OPCODE, WAITING_ADDRESSES, ERROR
  };

  private State state = State.WAITING_MODE;
  private final ByteReader byteReader = new ByteReader();
  private final SocketAddressReader socketAddressReader = new SocketAddressReader();
  private byte mode;
  private byte opcode;
  private int index;
  private final ArrayList<SocketAddress> socketAddresses = new ArrayList<>();

  @Override
  public ProcessStatus process(ByteBuffer buffer) {
    Objects.requireNonNull(buffer);
    if (state == State.DONE || state == State.ERROR) {
      throw new IllegalStateException();
    }
    if (state == State.WAITING_MODE) {
      processMode(buffer);
      if (state == State.ERROR) {
        reset();
        return ProcessStatus.ERROR;
      }
      if (state == State.WAITING_MODE) {
        return ProcessStatus.REFILL;
      }
    }

    if (state == State.WAITING_OPCODE) {
      processOpcode(buffer);
      if (state == State.ERROR) {
        reset();
        return ProcessStatus.ERROR;
      }
      if (state == State.WAITING_OPCODE) {
        return ProcessStatus.REFILL;
      }
    }

    for (; index < mode; index++) {
      var status = processSocketAddress(buffer);
      if (state == State.ERROR) {
        reset();
        return ProcessStatus.ERROR;
      }
      if (status == ProcessStatus.REFILL) {
        return ProcessStatus.REFILL;
      }
    }

    state = State.DONE;
    return ProcessStatus.DONE;
  }

  private void processMode(ByteBuffer buffer) {
    var status = byteReader.process(buffer);
    switch (status) {
      case DONE -> {
        mode = byteReader.get();
        if (mode >= MODE_COUNT || mode < 0) {
          state = State.ERROR;
          return;
        }
        byteReader.reset();
        state = State.WAITING_OPCODE;
      }
      case ERROR -> state = State.ERROR;
    }
  }

  private void processOpcode(ByteBuffer buffer) {
    var status = byteReader.process(buffer);
    switch (status) {
      case DONE -> {
        opcode = byteReader.get();
        if (opcode > OPCODE_COUNT || opcode <= 0) {
          state = State.ERROR;
          return;
        }
        byteReader.reset();
        state = State.WAITING_ADDRESSES;
      }
      case ERROR -> state = State.ERROR;
    }
  }

  private ProcessStatus processSocketAddress(ByteBuffer buffer) {
    var status = socketAddressReader.process(buffer);
    switch(status) {
      case DONE -> {
        var socketAddress = socketAddressReader.get();
        socketAddresses.add(socketAddress);
        socketAddressReader.reset();
      }
      case ERROR -> state = State.ERROR;
    }
    return status;
  }

  @Override
  public Header get() {
    if (state != State.DONE) {
      throw new IllegalStateException();
    }
    return switch (mode) {
      case 0 -> new Header(new TransmissionMode.Local(), opcode);
      case 1 -> new Header(new TransmissionMode.Broadcast(socketAddresses.get(0)), opcode);
      case 2 -> new Header(new TransmissionMode.Transfer(socketAddresses.get(0), socketAddresses.get(1)), opcode);
      default -> throw new IllegalStateException("Unexpected value: " + mode);
    };
  }

  @Override
  public void reset() {
    state = State.WAITING_MODE;
    byteReader.reset();
    socketAddressReader.reset();
    index = 0;
    socketAddresses.clear();
  }
}