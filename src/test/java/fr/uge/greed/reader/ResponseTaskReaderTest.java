package fr.uge.greed.reader;

import fr.uge.greed.packet.ResponseTask;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ResponseTaskReaderTest {

  @Test
  public void simple() {
    var responseTask = new ResponseTask(12L, (byte) 0, Optional.of("test"));
    var bb = ByteBuffer.allocate(1024);
    var response = StandardCharsets.UTF_8.encode("test");
    bb.putLong(12L)
        .put((byte) 0)
        .putInt(response.remaining())
        .put(response);

    var responseTaskReader = new ResponseTaskReader();
    assertEquals(Reader.ProcessStatus.DONE, responseTaskReader.process(bb));
    assertEquals(responseTask, responseTaskReader.get());
    assertEquals(0, bb.position());
    assertEquals(bb.capacity(), bb.limit());
  }

  @Test
  public void simple2() {
    var responseTask = new ResponseTask(12L, (byte) 1, Optional.empty());
    var bb = ByteBuffer.allocate(1024);
    bb.putLong(12L)
        .put((byte) 1);

    var responseTaskReader = new ResponseTaskReader();
    assertEquals(Reader.ProcessStatus.DONE, responseTaskReader.process(bb));
    assertEquals(responseTask, responseTaskReader.get());
    assertEquals(0, bb.position());
    assertEquals(bb.capacity(), bb.limit());
  }

  @Test
  public void reset() {
    var responseTask = new ResponseTask(12L, (byte) 0, Optional.of("test"));
    var responseTask2 = new ResponseTask(12L, (byte) 0, Optional.of("test2"));

    var bb = ByteBuffer.allocate(1024);
    var response = StandardCharsets.UTF_8.encode("test");
    var response2 = StandardCharsets.UTF_8.encode("test2");
    var expectedSize = Long.BYTES + Integer.BYTES + Byte.BYTES + response2.remaining();
    bb.putLong(12L)
        .put((byte) 0)
        .putInt(response.remaining())
        .put(response)
        .putLong(12L)
        .put((byte) 0)
        .putInt(response2.remaining())
        .put(response2);

    var responseTaskReader = new ResponseTaskReader();
    assertEquals(Reader.ProcessStatus.DONE, responseTaskReader.process(bb));
    assertEquals(responseTask, responseTaskReader.get());
    assertEquals(expectedSize, bb.position());
    assertEquals(bb.capacity(), bb.limit());

    responseTaskReader.reset();
    assertEquals(Reader.ProcessStatus.DONE, responseTaskReader.process(bb));
    assertEquals(responseTask2, responseTaskReader.get());
    assertEquals(0, bb.position());
    assertEquals(bb.capacity(), bb.limit());
  }

  @Test
  public void smallBuffer() {
    var responseTask = new ResponseTask(12L, (byte) 0, Optional.of("test"));
    var bb = ByteBuffer.allocate(1024);
    var response = StandardCharsets.UTF_8.encode("test");
    bb.putLong(12L)
        .put((byte) 0)
        .putInt(response.remaining())
        .put(response)
        .flip();
    var bbSmall = ByteBuffer.allocate(Byte.BYTES);

    var responseTaskReader = new ResponseTaskReader();
    while (bb.hasRemaining()) {
      bbSmall.put(bb.get());

      if (bb.hasRemaining()) {
        assertEquals(Reader.ProcessStatus.REFILL, responseTaskReader.process(bbSmall));
      } else {
        assertEquals(Reader.ProcessStatus.DONE, responseTaskReader.process(bbSmall));
      }
    }
    assertEquals(responseTask, responseTaskReader.get());
  }

  @Test
  public void errorGet() {
    var responseTaskReader = new ResponseTaskReader();
    assertThrows(IllegalStateException.class, () -> {
      var res = responseTaskReader.get();
    });
  }

  @Test
  public void errorNeg() {
    var responseTaskReader = new ResponseTaskReader();
    var bb = ByteBuffer.allocate(1024);
    bb.putLong(12L).put((byte) -1);
    assertEquals(Reader.ProcessStatus.ERROR, responseTaskReader.process(bb));
  }

  @Test
  public void errorTooBig() {
    var responseTaskReader = new ResponseTaskReader();
    var bb = ByteBuffer.allocate(1024);
    bb.putLong(12L).put((byte) 4);
    assertEquals(Reader.ProcessStatus.ERROR, responseTaskReader.process(bb));
  }
}