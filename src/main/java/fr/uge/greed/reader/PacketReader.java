package fr.uge.greed.reader;

import fr.uge.greed.*;
import fr.uge.greed.packet.*;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;

public class PacketReader implements Reader<Packet> {

  private enum State {
    DONE, WAITING_HEADER, WAITING_PAYLOAD, ERROR
  };

  private State state = State.WAITING_HEADER;
  private final ByteReader byteReader = new ByteReader();
  private final IntReader intReader = new IntReader();
  private final LongReader longReader = new LongReader();
  private final StringReader stringReader = new StringReader();
  private final SocketAddressReader socketAddressReader = new SocketAddressReader();
  private final ListSocketAddressReader listSocketAddressReader = new ListSocketAddressReader();
  private final HeaderReader headerReader = new HeaderReader();
  private Header header;
  private Packet packet;
  @SuppressWarnings("unchecked") // Safe cast, Trust the process
  private final List<Reader<? extends Payload>> readers = List.of(
      /* 0 */ new GenericReader<>(List.of(socketAddressReader), parts -> new Connection((SocketAddress) parts.get(0))),
      /* 1 */ new GenericReader<>(List.of(listSocketAddressReader), parts -> new Validation((List<SocketAddress>) parts.get(0))),
      /* 2 */ new GenericReader<>(List.of(), parts -> new RejectConnection()),
      /* 3 */ new GenericReader<>(List.of(socketAddressReader), parts -> new NewServer((SocketAddress) parts.get(0))),
      /* 4 */ new GenericReader<>(List.of(), parts -> new RequestState()),
      /* 5 */ new GenericReader<>(List.of(intReader), parts -> new ResponseState((int) parts.get(0))),
      /* 6 */ new GenericReader<>(
          List.of(longReader, stringReader, stringReader, longReader, longReader),
          parts -> new Task((long) parts.get(0), (String) parts.get(1), (String) parts.get(2), new Task.Range((long) parts.get(3), (long) parts.get(4)))
      ),
      /* 7 */ new GenericReader<>(List.of(longReader), parts -> new RejectTask((long) parts.get(0))),
      /* 8 */ new ResponseTaskReader(),
      /* 9 */ new GenericReader<>(List.of(longReader, byteReader, longReader), parts -> new AnnulationTask((long) parts.get(0), (byte) parts.get(1), (long) parts.get(2))),
      /* 10 */ new GenericReader<>(List.of(), parts -> new Disconnection()),
      /* 11 */ new GenericReader<>(List.of(listSocketAddressReader), parts -> new Reconnection((List<SocketAddress>) parts.get(0)))
  );

  @Override
  public ProcessStatus process(ByteBuffer buffer) {
    Objects.requireNonNull(buffer);
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
    }

    var reader = readers.get(header.opcode());
    var status = reader.process(buffer);
    if (status == Reader.ProcessStatus.ERROR || status == Reader.ProcessStatus.REFILL) {
      return status;
    }

    packet = new Packet(header, reader.get());

    state = State.DONE;
    return ProcessStatus.DONE;
  }

  private void processHeader(ByteBuffer buffer) {
    var status = headerReader.process(buffer);
    if (status == ProcessStatus.ERROR) {
      state = State.ERROR;
    } else if (status == ProcessStatus.REFILL) {
      return;
    }

    header = headerReader.get();
    headerReader.reset();
    state = State.WAITING_PAYLOAD;
  }

  @Override
  public Packet get() {
    if (state != State.DONE) {
      throw new IllegalStateException();
    }
    return packet;
  }

  @Override
  public void reset() {
    state = State.WAITING_HEADER;
    headerReader.reset();
    readers.forEach(Reader::reset);
  }
}