package fr.uge.greed.reader;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class IPAddressReaderTest {

    @Test
    public void simpleIPv4() {
        var address = "127.0.0.1";
        var bb = ByteBuffer.allocate(1024);
        bb.put((byte) 4)
            .put((byte) 127)
            .put((byte) 0)
            .put((byte) 0)
            .put((byte) 1);

        var reader = new IPAddressReader();
        assertEquals(Reader.ProcessStatus.DONE, reader.process(bb));
        assertEquals(address, reader.get());
        assertEquals(0, bb.position());
        assertEquals(bb.capacity(), bb.limit());
    }

    @Test
    public void simpleIPv6() {
        var address = "FDEC:BA98:7654:3210:ADFC:BDFF:2990:FFFF";
        var bb = ByteBuffer.allocate(1024);
        bb.put((byte) 6)
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
            .put((byte) 0xFF);

        var reader = new IPAddressReader();
        assertEquals(Reader.ProcessStatus.DONE, reader.process(bb));
        assertEquals(address, reader.get());
        assertEquals(0, bb.position());
        assertEquals(bb.capacity(), bb.limit());
    }

    @Test
    public void resetIPv4() {
        var address = "127.0.0.1";
        var address2 = "192.168.0.1";
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
        assertEquals(address, reader.get());
        assertEquals(Byte.BYTES * 5, bb.position());
        assertEquals(bb.capacity(), bb.limit());

        reader.reset();
        assertEquals(Reader.ProcessStatus.DONE, reader.process(bb));
        assertEquals(address2, reader.get());
        assertEquals(0, bb.position());
        assertEquals(bb.capacity(), bb.limit());
    }

    @Test
    public void smallBuffer() {
        var address = "127.0.0.1";
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
        assertEquals(address, reader.get());
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