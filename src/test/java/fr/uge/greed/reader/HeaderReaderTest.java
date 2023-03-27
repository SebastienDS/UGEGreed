package fr.uge.greed.reader;

import fr.uge.greed.Header;
import fr.uge.greed.SocketAddress;
import fr.uge.greed.TransmissionMode;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class HeaderReaderTest {

  @Test
  public void simpleLocal() {
    var header = new Header(new TransmissionMode.Local(), (byte)1);
    var bb = ByteBuffer.allocate(1024);
    bb.put((byte) 0)
        .put((byte) 1);

    var reader = new HeaderReader();
    assertEquals(Reader.ProcessStatus.DONE, reader.process(bb));
    assertEquals(header, reader.get());
    assertEquals(0, bb.position());
    assertEquals(bb.capacity(), bb.limit());
  }

  @Test
  public void simpleBroadcast() {
    var socketAddress = new SocketAddress("127.0.0.1", 7777);
    var header = new Header(new TransmissionMode.Broadcast(socketAddress), (byte)1);

    var bb = ByteBuffer.allocate(1024);
    bb.put((byte)1)
        .put((byte) 1)
        .put((byte) 4)
        .put((byte) 127)
        .put((byte) 0)
        .put((byte) 0)
        .put((byte) 1)
        .putInt(socketAddress.port());

    var reader = new HeaderReader();
    assertEquals(Reader.ProcessStatus.DONE, reader.process(bb));
    assertEquals(header, reader.get());
    assertEquals(0, bb.position());
    assertEquals(bb.capacity(), bb.limit());
  }

  @Test
  public void simpleTransfer() {
    var source = new SocketAddress("127.0.0.1", 7777);
    var destination = new SocketAddress("192.168.0.1", 7777);
    var header = new Header(new TransmissionMode.Transfer(source, destination), (byte)1);

    var bb = ByteBuffer.allocate(1024);
    bb.put((byte)2)
        .put((byte) 1)
        .put((byte) 4)
        .put((byte) 127)
        .put((byte) 0)
        .put((byte) 0)
        .put((byte) 1)
        .putInt(source.port())
        .put((byte) 4)
        .put((byte) 192)
        .put((byte) 168)
        .put((byte) 0)
        .put((byte) 1)
        .putInt(destination.port());

    var reader = new HeaderReader();
    assertEquals(Reader.ProcessStatus.DONE, reader.process(bb));
    assertEquals(header, reader.get());
    assertEquals(0, bb.position());
    assertEquals(bb.capacity(), bb.limit());
  }

  @Test
  public void reset() {
    var source = new SocketAddress("127.0.0.1", 7777);
    var destination = new SocketAddress("192.168.0.1", 8888);
    var headerBroadcast = new Header(new TransmissionMode.Broadcast(source), (byte)1);
    var headerTransfer = new Header(new TransmissionMode.Transfer(source, destination), (byte)1);

    var bb = ByteBuffer.allocate(1024);
    bb.put((byte)1)
        .put((byte) 1)
        .put((byte) 4)
        .put((byte) 127)
        .put((byte) 0)
        .put((byte) 0)
        .put((byte) 1)
        .putInt(source.port());

    bb.put((byte)2)
        .put((byte) 1)
        .put((byte) 4)
        .put((byte) 127)
        .put((byte) 0)
        .put((byte) 0)
        .put((byte) 1)
        .putInt(source.port())
        .put((byte) 4)
        .put((byte) 192)
        .put((byte) 168)
        .put((byte) 0)
        .put((byte) 1)
        .putInt(destination.port());

    var reader = new HeaderReader();
    assertEquals(Reader.ProcessStatus.DONE, reader.process(bb));
    assertEquals(headerBroadcast, reader.get());
    assertEquals(Byte.BYTES * 12 + Integer.BYTES * 2, bb.position());
    assertEquals(bb.capacity(), bb.limit());

    reader.reset();
    assertEquals(Reader.ProcessStatus.DONE, reader.process(bb));
    assertEquals(headerTransfer, reader.get());
    assertEquals(0, bb.position());
    assertEquals(bb.capacity(), bb.limit());
  }

  @Test
  public void smallBuffer() {
    var socketAddress = new SocketAddress("127.0.0.1", 7777);
    var header = new Header(new TransmissionMode.Broadcast(socketAddress), (byte)1);

    var bb = ByteBuffer.allocate(1024);
    bb.put((byte)1)
        .put((byte) 1)
        .put((byte) 4)
        .put((byte) 127)
        .put((byte) 0)
        .put((byte) 0)
        .put((byte) 1)
        .putInt(socketAddress.port())
        .flip();

    var bbSmall = ByteBuffer.allocate(1);
    var reader = new HeaderReader();
    while (bb.hasRemaining()) {
      bbSmall.put(bb.get());
      if (bb.hasRemaining()) {
        assertEquals(Reader.ProcessStatus.REFILL, reader.process(bbSmall));
      } else {
        assertEquals(Reader.ProcessStatus.DONE, reader.process(bbSmall));
      }
    }
    assertEquals(header, reader.get());
  }

  @Test
  public void errorGet() {
    var reader = new HeaderReader();
    assertThrows(IllegalStateException.class, () -> {
      var res = reader.get();
    });
  }

  @Test
  public void errorMode() {
    var reader = new HeaderReader();
    var bb = ByteBuffer.allocate(1024);
    bb.put((byte) -1);
    assertEquals(Reader.ProcessStatus.ERROR, reader.process(bb));
  }

  @Test
  public void errorMode2() {
    var reader = new HeaderReader();
    var bb = ByteBuffer.allocate(1024);
    bb.put((byte) 3);
    assertEquals(Reader.ProcessStatus.ERROR, reader.process(bb));
  }

  @Test
  public void errorOpcode() {
    var reader = new HeaderReader();
    var bb = ByteBuffer.allocate(1024);
    bb.put((byte) 0).put((byte)0);
    assertEquals(Reader.ProcessStatus.ERROR, reader.process(bb));
  }

  @Test
  public void errorOpcode2() {
    var reader = new HeaderReader();
    var bb = ByteBuffer.allocate(1024);
    bb.put((byte) 0).put((byte)13);
    assertEquals(Reader.ProcessStatus.ERROR, reader.process(bb));
  }
}