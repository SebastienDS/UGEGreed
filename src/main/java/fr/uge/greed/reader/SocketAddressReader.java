package fr.uge.greed.reader;

import fr.uge.greed.SocketAddress;

import java.nio.ByteBuffer;
import java.util.Objects;

public class SocketAddressReader implements Reader<SocketAddress> {
  private enum State {
    DONE, WAITING_ADDRESS_IP, WAITING_PORT, ERROR
  };

  private State state = State.WAITING_ADDRESS_IP;
  private final IntReader intReader = new IntReader();
  private final IPAddressReader ipAddressReader = new IPAddressReader();
  private String addressIP;
  private int port;

  @Override
  public ProcessStatus process(ByteBuffer buffer) {
    Objects.requireNonNull(buffer);
    if (state == State.DONE || state == State.ERROR) {
      throw new IllegalStateException();
    }
    if (state == State.WAITING_ADDRESS_IP) {
      processAddressIP(buffer);
      if (state == State.ERROR) {
        reset();
        return ProcessStatus.ERROR;
      }
    }
    if (state != State.WAITING_PORT) {
      return ProcessStatus.REFILL;
    }
    processPort(buffer);
    if (state == State.ERROR) {
      reset();
      return ProcessStatus.ERROR;
    }
    if (state != State.DONE) {
      return ProcessStatus.REFILL;
    }

    return ProcessStatus.DONE;
  }

  private void processPort(ByteBuffer buffer) {
    var status = intReader.process(buffer);
    switch (status) {
      case DONE -> {
        port = intReader.get();
        if (port > 65_535 || port < 0) {
          state = State.ERROR;
          return;
        }
        intReader.reset();
        state = State.DONE;
      }
      case ERROR -> state = State.ERROR;
    }
  }

  private void processAddressIP(ByteBuffer buffer) {
    var status = ipAddressReader.process(buffer);
    switch(status) {
      case DONE -> {
        addressIP = ipAddressReader.get();
        ipAddressReader.reset();
        state = State.WAITING_PORT;
      }
      case ERROR -> state = State.ERROR;
    }
  }

  @Override
  public SocketAddress get() {
    if (state != State.DONE) {
      throw new IllegalStateException();
    }
    return new SocketAddress(addressIP, port);
  }

  @Override
  public void reset() {
    state = State.WAITING_ADDRESS_IP;
    ipAddressReader.reset();
    intReader.reset();
  }
}