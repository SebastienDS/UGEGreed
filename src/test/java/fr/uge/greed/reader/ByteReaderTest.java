package fr.uge.greed.reader;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ByteReaderTest {

  @Test
  public void simple() {
    byte value = 10;
    var bb = ByteBuffer.allocate(1024);
    bb.put(value);

    var byteReader = new ByteReader();
    assertEquals(Reader.ProcessStatus.DONE, byteReader.process(bb));
    assertEquals(value, byteReader.get());
    assertEquals(0, bb.position());
    assertEquals(bb.capacity(), bb.limit());
  }

  @Test
  public void reset() {
    byte value1 = 10;
    byte value2 = 20;
    var bb = ByteBuffer.allocate(1024);
    bb.put(value1).put(value2);

    var byteReader = new ByteReader();
    assertEquals(Reader.ProcessStatus.DONE, byteReader.process(bb));
    assertEquals(value1, byteReader.get());
    assertEquals(Byte.BYTES, bb.position());
    assertEquals(bb.capacity(), bb.limit());

    byteReader.reset();
    assertEquals(Reader.ProcessStatus.DONE, byteReader.process(bb));
    assertEquals(value2, byteReader.get());
    assertEquals(0, bb.position());
    assertEquals(bb.capacity(), bb.limit());
  }

  @Test
  public void errorGet() {
    var byteReader = new ByteReader();
    assertThrows(IllegalStateException.class, () -> {
      var res = byteReader.get();
    });
  }
}