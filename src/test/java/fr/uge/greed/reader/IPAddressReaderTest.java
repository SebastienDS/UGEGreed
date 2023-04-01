package fr.uge.greed.reader;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

public class IPAddressReaderTest {

  @Test
  public void simpleIPv4() {
    var address = new byte[] {127, 0, 0, 1};
    var bb = ByteBuffer.allocate(1024);
    bb.put((byte) 4)
        .put((byte) 127)
        .put((byte) 0)
        .put((byte) 0)
        .put((byte) 1);

    var reader = new IPAddressReader();
    assertEquals(Reader.ProcessStatus.DONE, reader.process(bb));
    assertArrayEquals(address, reader.get());
    assertEquals(0, bb.position());
    assertEquals(bb.capacity(), bb.limit());
  }

  @Test
  public void simpleIPv6() {
    var address = new byte[] {(byte) 0xFD, (byte) 0xEC, (byte) 0xBA, (byte) 0x98, (byte) 0x76, (byte) 0x54, (byte) 0x32,
        (byte) 0x10, (byte) 0xAD, (byte) 0xFC, (byte) 0xBD, (byte) 0xFF, (byte) 0x29, (byte) 0x90, (byte) 0xFF, (byte) 0xFF};
    var bb = ByteBuffer.allocate(1024);
    bb.put((byte) 6);
    for (var value : address) {
      bb.put(value);
    }

    var reader = new IPAddressReader();
    assertEquals(Reader.ProcessStatus.DONE, reader.process(bb));
    assertArrayEquals(address, reader.get());
    assertEquals(0, bb.position());
    assertEquals(bb.capacity(), bb.limit());
  }

  @Test
  public void resetIPv4() {
    var address = new byte[] {127, 0, 0, 1};
    var address2 = new byte[] {(byte) 192, (byte) 168, 0, 1};
    var bb = ByteBuffer.allocate(1024);
    bb.put((byte) 4)
        .put((byte) 127)
        .put((byte) 0)
        .put((byte) 0)
        .put((byte) 1);

    bb.put((byte) 4)
        .put((byte) 192)
        .put((byte) 168)
        .put((byte) 0)
        .put((byte) 1);

    var reader = new IPAddressReader();
    assertEquals(Reader.ProcessStatus.DONE, reader.process(bb));
    assertArrayEquals(address, reader.get());
    assertEquals(Byte.BYTES * 5, bb.position());
    assertEquals(bb.capacity(), bb.limit());

    reader.reset();
    assertEquals(Reader.ProcessStatus.DONE, reader.process(bb));
    assertArrayEquals(address2, reader.get());
    assertEquals(0, bb.position());
    assertEquals(bb.capacity(), bb.limit());
  }

  @Test
  public void smallBuffer() {
    var address = new byte[] {127, 0, 0, 1};
    var bb = ByteBuffer.allocate(1024);
    bb.put((byte) 4)
        .put((byte) 127)
        .put((byte) 0)
        .put((byte) 0)
        .put((byte) 1)
        .flip();

    var bbSmall = ByteBuffer.allocate(1);
    var reader = new IPAddressReader();
    while (bb.hasRemaining()) {
      bbSmall.put(bb.get());
      if (bb.hasRemaining()) {
        assertEquals(Reader.ProcessStatus.REFILL, reader.process(bbSmall));
      } else {
        assertEquals(Reader.ProcessStatus.DONE, reader.process(bbSmall));
      }
    }
    assertArrayEquals(address, reader.get());
  }

  @Test
  public void errorGet() {
    var reader = new IPAddressReader();
    assertThrows(IllegalStateException.class, () -> {
      var res = reader.get();
    });
  }

  @Test
  public void errorFormat() {
    var reader = new IPAddressReader();
    var bb = ByteBuffer.allocate(1024);
    bb.put((byte) 5)
        .put((byte) 127)
        .put((byte) 0)
        .put((byte) 0)
        .put((byte) 1);
    assertEquals(Reader.ProcessStatus.ERROR, reader.process(bb));
  }
}