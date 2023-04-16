package fr.uge.greed.reader;

import fr.uge.greed.SocketAddress;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class GenericReaderTest {
  private record Empty() {}
  private record Message(String login, String content) {}
  private record CompletePacket(byte opcode, int n, String content, SocketAddress address) {}

  @Test
  public void simple() {
    var reader = GenericReader.create(builder -> Empty::new);
    var bb = ByteBuffer.allocate(0);
    assertEquals(Reader.ProcessStatus.DONE, reader.process(bb));
    assertEquals(new Empty(), reader.get());
  }

  @Test
  public void simpleMessage() {
    var stringReader = new StringReader();
    var messageReader = GenericReader.create(builder -> {
      var login = builder.add(stringReader);
      var message = builder.add(stringReader);
      return () ->  new Message(login.get(), message.get());
    });

    var message = new Message("login", "content");
    var bb = ByteBuffer.allocate(1024);
    var encodedLogin = StandardCharsets.UTF_8.encode(message.login);
    var encodedContent = StandardCharsets.UTF_8.encode(message.content);

    bb.putInt(encodedLogin.remaining())
        .put(encodedLogin)
        .putInt(encodedContent.remaining())
        .put(encodedContent);

    assertEquals(Reader.ProcessStatus.DONE, messageReader.process(bb));
    assertEquals(message, messageReader.get());
    assertEquals(0, bb.position());
    assertEquals(bb.capacity(), bb.limit());
  }

  @Test
  public void completePacket() {
    var reader = GenericReader.create(builder -> {
      var a = builder.add(new ByteReader());
      var b = builder.add(new IntReader());
      var c = builder.add(new StringReader());
      var d = builder.add(new SocketAddressReader());
      return () ->  new CompletePacket(a.get(), b.get(), c.get(), d.get());
    });

    var packet = new CompletePacket((byte)1, 10, "content", new SocketAddress("127.0.0.1", 7777));

    var bb = ByteBuffer.allocate(1024);
    var encodedContent = StandardCharsets.UTF_8.encode(packet.content);

    bb.put((byte)1)
        .putInt(10)
        .putInt(encodedContent.remaining())
        .put(encodedContent)
        .put((byte) 4)
        .put((byte) 127)
        .put((byte) 0)
        .put((byte) 0)
        .put((byte) 1)
        .putInt(7777);

    assertEquals(Reader.ProcessStatus.DONE, reader.process(bb));
    assertEquals(packet, reader.get());
    assertEquals(0, bb.position());
    assertEquals(bb.capacity(), bb.limit());
  }

  @Test
  public void reset() {
    var stringReader = new StringReader();
    var messageReader = GenericReader.create(builder -> {
      var login = builder.add(stringReader);
      var message = builder.add(stringReader);
      return () ->  new Message(login.get(), message.get());
    });

    var message = new Message("login", "content");
    var message2 = new Message("login2", "content2");
    var bb = ByteBuffer.allocate(1024);
    var encodedLogin = StandardCharsets.UTF_8.encode(message.login);
    var encodedContent = StandardCharsets.UTF_8.encode(message.content);
    var encodedLogin2 = StandardCharsets.UTF_8.encode(message2.login);
    var encodedContent2 = StandardCharsets.UTF_8.encode(message2.content);

    var expectedPosition = 2 * Integer.BYTES + encodedLogin2.remaining() + encodedContent2.remaining();

    bb.putInt(encodedLogin.remaining())
        .put(encodedLogin)
        .putInt(encodedContent.remaining())
        .put(encodedContent)
        .putInt(encodedLogin2.remaining())
        .put(encodedLogin2)
        .putInt(encodedContent2.remaining())
        .put(encodedContent2);

    assertEquals(Reader.ProcessStatus.DONE, messageReader.process(bb));
    assertEquals(message, messageReader.get());
    assertEquals(expectedPosition, bb.position());
    assertEquals(bb.capacity(), bb.limit());

    messageReader.reset();
    assertEquals(Reader.ProcessStatus.DONE, messageReader.process(bb));
    assertEquals(message2, messageReader.get());
    assertEquals(0, bb.position());
    assertEquals(bb.capacity(), bb.limit());
  }

  @Test
  public void smallBuffer() {
    var stringReader = new StringReader();
    var messageReader = GenericReader.create(builder -> {
      var login = builder.add(stringReader);
      var message = builder.add(stringReader);
      return () ->  new Message(login.get(), message.get());
    });

    var message = new Message("login", "content");
    var bb = ByteBuffer.allocate(1024);
    var encodedLogin = StandardCharsets.UTF_8.encode(message.login);
    var encodedContent = StandardCharsets.UTF_8.encode(message.content);

    bb.putInt(encodedLogin.remaining())
        .put(encodedLogin)
        .putInt(encodedContent.remaining())
        .put(encodedContent)
        .flip();

    var bbSmall = ByteBuffer.allocate(1);
    while (bb.hasRemaining()) {
      bbSmall.put(bb.get());
      if (bb.hasRemaining()) {
        assertEquals(Reader.ProcessStatus.REFILL, messageReader.process(bbSmall));
      } else {
        assertEquals(Reader.ProcessStatus.DONE, messageReader.process(bbSmall));
      }
    }
    assertEquals(message, messageReader.get());
  }

  @Test
  public void errorGet() {
    var stringReader = new StringReader();
    var messageReader = GenericReader.create(builder -> {
      var login = builder.add(stringReader);
      var message = builder.add(stringReader);
      return () ->  new Message(login.get(), message.get());
    });
    assertThrows(IllegalStateException.class, () -> {
        var res = messageReader.get();
    });
  }

}