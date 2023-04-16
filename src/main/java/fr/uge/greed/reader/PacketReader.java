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
  private final List<Reader<? extends Payload>> readers = List.of(
      /* 0 */ GenericReader.create(builder -> {
        var authentication = builder.add(stringReader);
        var address = builder.add(socketAddressReader);
        return () -> new Connection(authentication.get(), address.get());
      }),
      /* 1 */ GenericReader.create(builder -> {
        var addresses = builder.add(listSocketAddressReader);
        return () -> new Validation(addresses.get());
      }),
      /* 2 */ GenericReader.create(builder -> RejectConnection::new),
      /* 3 */ GenericReader.create(builder -> {
        var address = builder.add(socketAddressReader);
        return () -> new NewServer(address.get());
      }),
      /* 4 */ GenericReader.create(builder -> RequestState::new),
      /* 5 */ GenericReader.create(builder -> {
        var tasksInProgress = builder.add(intReader);
        return () -> new ResponseState(tasksInProgress.get());
      }),
      /* 6 */ GenericReader.create(builder -> {
        var id = builder.add(longReader);
        var url = builder.add(stringReader);
        var className = builder.add(stringReader);
        var from = builder.add(longReader);
        var to = builder.add(longReader);
        return () -> new Task(id.get(), url.get(), className.get(), new Task.Range(from.get(), to.get()));
      }),
      /* 7 */ GenericReader.create(builder -> {
        var id = builder.add(longReader);
        return () -> new RejectTask(id.get());
      }),
      /* 8 */ new ResponseTaskReader(),
      /* 9 */ GenericReader.create(builder -> {
        var id = builder.add(longReader);
        var status = builder.add(byteReader);
        var startRemainingValues = builder.add(longReader);
        return () -> new AnnulationTask(id.get(), status.get(), startRemainingValues.get());
      }),
      /* 10 */ GenericReader.create(builder -> Disconnection::new),
      /* 11 */ GenericReader.create(builder -> {
        var authentication = builder.add(stringReader);
        var address = builder.add(socketAddressReader);
        var addresses = builder.add(listSocketAddressReader);
        return () -> new Reconnection(authentication.get(), address.get(), addresses.get());
      })
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