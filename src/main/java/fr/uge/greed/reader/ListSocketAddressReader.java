package fr.uge.greed.reader;

import fr.uge.greed.SocketAddress;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ListSocketAddressReader implements Reader<List<SocketAddress>> {

  private enum State {
    DONE, WAITING_SOCKET_ADDRESS, WAITING_INT, ERROR
  };

  private State state = State.WAITING_INT;
  private final SocketAddressReader socketAddressReader = new SocketAddressReader();
  private final IntReader intReader = new IntReader();
  private int count;
  private int index;
  private final ArrayList<SocketAddress> listSocketAddress = new ArrayList<>();


  @Override
  public ProcessStatus process(ByteBuffer buffer) {
    Objects.requireNonNull(buffer);
    if (state == State.DONE || state == State.ERROR) {
      throw new IllegalStateException();
    }
    if (state == State.WAITING_INT) {
      processCount(buffer);
      if (state == State.ERROR) {
        reset();
        return ProcessStatus.ERROR;
      }
    }
    if (state != State.WAITING_SOCKET_ADDRESS) {
      return ProcessStatus.REFILL;
    }
    for (; index < count; index++) {
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

  private void processCount(ByteBuffer buffer) {
    var status = intReader.process(buffer);
    switch (status) {
      case DONE -> {
        count = intReader.get();
        if (count < 0) {
          state = State.ERROR;
          return;
        }
        intReader.reset();
        state = State.WAITING_SOCKET_ADDRESS;
      }
      case ERROR -> state = State.ERROR;
    }
  }

  private ProcessStatus processSocketAddress(ByteBuffer buffer) {
    var status = socketAddressReader.process(buffer);
    switch(status) {
      case DONE -> {
        var socketAddress = socketAddressReader.get();
        listSocketAddress.add(socketAddress);
        socketAddressReader.reset();
      }
      case ERROR -> state = State.ERROR;
    }
    return status;
  }

  @Override
  public List<SocketAddress> get() {
    if (state != State.DONE) {
      throw new IllegalStateException();
    }
    return List.copyOf(listSocketAddress);
  }

  @Override
  public void reset() {
    state = State.WAITING_INT;
    intReader.reset();
    socketAddressReader.reset();
    index = 0;
    listSocketAddress.clear();
  }
}