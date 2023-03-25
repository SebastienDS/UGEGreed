package fr.uge.greed.reader;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class LongReaderTest {

    @Test
    public void simple() {
        long value = 10;
        var bb = ByteBuffer.allocate(1024);
        bb.putLong(value);

        var longReader = new LongReader();
        assertEquals(Reader.ProcessStatus.DONE, longReader.process(bb));
        assertEquals(value, longReader.get());
        assertEquals(0, bb.position());
        assertEquals(bb.capacity(), bb.limit());
    }

    @Test
    public void reset() {
        long value1 = 10;
        long value2 = 20;
        var bb = ByteBuffer.allocate(1024);
        bb.putLong(value1).putLong(value2);

        var longReader = new LongReader();
        assertEquals(Reader.ProcessStatus.DONE, longReader.process(bb));
        assertEquals(value1, longReader.get());
        assertEquals(Long.BYTES, bb.position());
        assertEquals(bb.capacity(), bb.limit());

        longReader.reset();
        assertEquals(Reader.ProcessStatus.DONE, longReader.process(bb));
        assertEquals(value2, longReader.get());
        assertEquals(0, bb.position());
        assertEquals(bb.capacity(), bb.limit());
    }

    @Test
    public void smallBuffer() {
        long value = 10;
        var bb = ByteBuffer.allocate(1024);
        bb.putLong(value).flip();
        var bbSmall = ByteBuffer.allocate(Byte.BYTES);

        var lr = new LongReader();
        while (bb.hasRemaining()) {
            bbSmall.put(bb.get());

            if (bb.hasRemaining()) {
                assertEquals(Reader.ProcessStatus.REFILL, lr.process(bbSmall));
            } else {
                assertEquals(Reader.ProcessStatus.DONE, lr.process(bbSmall));
            }
        }
        assertEquals(value, lr.get());
    }

    @Test
    public void errorGet() {
        var lr = new LongReader();
        assertThrows(IllegalStateException.class, () -> {
            var res = lr.get();
        });
    }
}