package fr.uge.greed.reader;

import fr.uge.greed.SocketAddress;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ListSocketAddressReaderTest {

  @Test
  public void simpleIPv4() {
    var socketAddress = new SocketAddress("127.0.0.1", 7777);
    var socketAddress2 = new SocketAddress("128.0.0.1", 8888);
    var listSocketAddress = List.of(socketAddress, socketAddress2);
    var bb = ByteBuffer.allocate(1024);
    bb.putInt(2)
        .put((byte) 4)
        .put((byte) 127)
        .put((byte) 0)
        .put((byte) 0)
        .put((byte) 1)
        .putInt(socketAddress.port())
        .put((byte) 4)
        .put((byte) 128)
        .put((byte) 0)
        .put((byte) 0)
        .put((byte) 1)
        .putInt(socketAddress2.port());

    var reader = new ListSocketAddressReader();
    assertEquals(Reader.ProcessStatus.DONE, reader.process(bb));
    assertEquals(listSocketAddress, reader.get());
    assertEquals(0, bb.position());
    assertEquals(bb.capacity(), bb.limit());
  }

  @Test
  public void simpleIPv6() {
    var socketAddress = new SocketAddress("FDEC:BA98:7654:3210:ADFC:BDFF:2990:FFFF", 7777);
    var socketAddress2 = new SocketAddress("FDEC:BA98:7654:3210:ADFC:BDFF:2990:FFFF", 6666);
    var listSocketAddress = List.of(socketAddress, socketAddress2);

    var bb = ByteBuffer.allocate(1024);
    bb.putInt(2)
        .put((byte) 6)
        .put((byte) 0xFD)
        .put((byte) 0xEC)
        .put((byte) 0xBA)
        .put((byte) 0x98)
        .put((byte) 0x76)
        .put((byte) 0x54)
        .put((byte) 0x32)
        .put((byte) 0x10)
        .put((byte) 0xAD)
        .put((byte) 0xFC)
        .put((byte) 0xBD)
        .put((byte) 0xFF)
        .put((byte) 0x29)
        .put((byte) 0x90)
        .put((byte) 0xFF)
        .put((byte) 0xFF)
        .putInt(socketAddress.port())
        .put((byte) 6)
        .put((byte) 0xFD)
        .put((byte) 0xEC)
        .put((byte) 0xBA)
        .put((byte) 0x98)
        .put((byte) 0x76)
        .put((byte) 0x54)
        .put((byte) 0x32)
        .put((byte) 0x10)
        .put((byte) 0xAD)
        .put((byte) 0xFC)
        .put((byte) 0xBD)
        .put((byte) 0xFF)
        .put((byte) 0x29)
        .put((byte) 0x90)
        .put((byte) 0xFF)
        .put((byte) 0xFF)
        .putInt(socketAddress2.port());

    var reader = new ListSocketAddressReader();
    assertEquals(Reader.ProcessStatus.DONE, reader.process(bb));
    assertEquals(listSocketAddress, reader.get());
    assertEquals(0, bb.position());
    assertEquals(bb.capacity(), bb.limit());
  }

  @Test
  public void resetIPv4() {
    var socketAddress = new SocketAddress("127.0.0.1", 7777);
    var socketAddress2 = new SocketAddress("128.0.0.1", 8888);
    var listSocketAddress = List.of(socketAddress, socketAddress2);
    var bb = ByteBuffer.allocate(1024);
    bb.putInt(2)
        .put((byte) 4)
        .put((byte) 127)
        .put((byte) 0)
        .put((byte) 0)
        .put((byte) 1)
        .putInt(socketAddress.port())
        .put((byte) 4)
        .put((byte) 128)
        .put((byte) 0)
        .put((byte) 0)
        .put((byte) 1)
        .putInt(socketAddress2.port());

    bb.putInt(2)
        .put((byte) 4)
        .put((byte) 127)
        .put((byte) 0)
        .put((byte) 0)
        .put((byte) 1)
        .putInt(socketAddress.port())
        .put((byte) 4)
        .put((byte) 128)
        .put((byte) 0)
        .put((byte) 0)
        .put((byte) 1)
        .putInt(socketAddress2.port());

    var reader = new ListSocketAddressReader();
    assertEquals(Reader.ProcessStatus.DONE, reader.process(bb));
    assertEquals(listSocketAddress, reader.get());
    assertEquals((Byte.BYTES * 5 + Integer.BYTES) * 2 + Integer.BYTES , bb.position());
    assertEquals(bb.capacity(), bb.limit());

    reader.reset();
    assertEquals(Reader.ProcessStatus.DONE, reader.process(bb));
    assertEquals(listSocketAddress, reader.get());
    assertEquals(0, bb.position());
    assertEquals(bb.capacity(), bb.limit());
  }

  @Test
  public void smallBuffer() {
    var socketAddress = new SocketAddress("127.0.0.1", 7777);
    var socketAddress2 = new SocketAddress("128.0.0.1", 8888);
    var listSocketAddress = List.of(socketAddress, socketAddress2);
    var bb = ByteBuffer.allocate(1024);
    bb.putInt(2)
        .put((byte) 4)
        .put((byte) 127)
        .put((byte) 0)
        .put((byte) 0)
        .put((byte) 1)
        .putInt(socketAddress.port())
        .put((byte) 4)
        .put((byte) 128)
        .put((byte) 0)
        .put((byte) 0)
        .put((byte) 1)
        .putInt(socketAddress2.port())
        .flip();

    var bbSmall = ByteBuffer.allocate(1);
    var reader = new ListSocketAddressReader();
    while (bb.hasRemaining()) {
      bbSmall.put(bb.get());
      if (bb.hasRemaining()) {
        assertEquals(Reader.ProcessStatus.REFILL, reader.process(bbSmall));
      } else {
        assertEquals(Reader.ProcessStatus.DONE, reader.process(bbSmall));
      }
    }
    assertEquals(listSocketAddress, reader.get());
  }

  @Test
  public void errorGet() {
    var reader = new ListSocketAddressReader();
    assertThrows(IllegalStateException.class, () -> {
      var res = reader.get();
    });
  }

  @Test
  public void errorCount() {
    var reader = new ListSocketAddressReader();
    var bb = ByteBuffer.allocate(1024);
    bb.putInt(-1);
    assertEquals(Reader.ProcessStatus.ERROR, reader.process(bb));
  }
}