package fr.uge.greed.reader;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class IntReaderTest {

    @Test
    public void simple() {
        int value = 10;
        var bb = ByteBuffer.allocate(1024);
        bb.putInt(value);

        var intReader = new IntReader();
        assertEquals(Reader.ProcessStatus.DONE, intReader.process(bb));
        assertEquals(value, intReader.get());
        assertEquals(0, bb.position());
        assertEquals(bb.capacity(), bb.limit());
    }

    @Test
    public void reset() {
        int value1 = 10;
        int value2 = 20;
        var bb = ByteBuffer.allocate(1024);
        bb.putInt(value1).putInt(value2);

        var intReader = new IntReader();
        assertEquals(Reader.ProcessStatus.DONE, intReader.process(bb));
        assertEquals(value1, intReader.get());
        assertEquals(Integer.BYTES, bb.position());
        assertEquals(bb.capacity(), bb.limit());

        intReader.reset();
        assertEquals(Reader.ProcessStatus.DONE, intReader.process(bb));
        assertEquals(value2, intReader.get());
        assertEquals(0, bb.position());
        assertEquals(bb.capacity(), bb.limit());
    }

    @Test
    public void smallBuffer() {
        int value = 10;
        var bb = ByteBuffer.allocate(1024);
        bb.putInt(value).flip();
        var bbSmall = ByteBuffer.allocate(Byte.BYTES);

        var intReader = new IntReader();
        while (bb.hasRemaining()) {
            bbSmall.put(bb.get());

            if (bb.hasRemaining()) {
                assertEquals(Reader.ProcessStatus.REFILL, intReader.process(bbSmall));
            } else {
                assertEquals(Reader.ProcessStatus.DONE, intReader.process(bbSmall));
            }
        }
        assertEquals(value, intReader.get());
    }

    @Test
    public void errorGet() {
        var intReader = new IntReader();
        assertThrows(IllegalStateException.class, () -> {
            var res = intReader.get();
        });
    }
}