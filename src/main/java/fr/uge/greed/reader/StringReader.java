package fr.uge.greed.reader;

import fr.uge.greed.reader.IntReader;
import fr.uge.greed.reader.Reader;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class StringReader implements Reader<String> {
    private static final int BUFFER_SIZE = 1024;

    private enum State {
        DONE, WAITING_INT, WAITING_STRING, ERROR
    };

    private State state = State.WAITING_INT;
    private final ByteBuffer internalBuffer = ByteBuffer.allocate(BUFFER_SIZE); // write-mode
    private final IntReader intReader = new IntReader();
    private String value;

    @Override
    public ProcessStatus process(ByteBuffer buffer) {
        Objects.requireNonNull(buffer);
        if (state == State.DONE || state == State.ERROR) {
            throw new IllegalStateException();
        }
        if (state == State.WAITING_INT) {
            processSize(buffer);
            if (state == State.ERROR) {
                reset();
                return ProcessStatus.ERROR;
            }
        }
        if (state != State.WAITING_STRING) {
            return ProcessStatus.REFILL;
        }
        buffer.flip();
        try {
            if (buffer.remaining() <= internalBuffer.remaining()) {
                internalBuffer.put(buffer);
            } else {
                var oldLimit = buffer.limit();
                buffer.limit(internalBuffer.remaining());
                internalBuffer.put(buffer);
                buffer.limit(oldLimit);
            }
        } finally {
            buffer.compact();
        }
        if (internalBuffer.hasRemaining()) {
            return ProcessStatus.REFILL;
        }
        state = State.DONE;
        internalBuffer.flip();
        value = StandardCharsets.UTF_8.decode(internalBuffer).toString();
        return ProcessStatus.DONE;
    }

    private void processSize(ByteBuffer buffer) {
        var status = intReader.process(buffer);
        switch (status) {
            case DONE -> {
                var size = intReader.get();
                if (size > BUFFER_SIZE || size < 0) {
                    state = State.ERROR;
                    return;
                }
                internalBuffer.limit(size);
                intReader.reset();
                state = State.WAITING_STRING;
            }
            case ERROR -> state = State.ERROR;
        }
    }

    @Override
    public String get() {
        if (state != State.DONE) {
            throw new IllegalStateException();
        }
        return value;
    }

    @Override
    public void reset() {
        state = State.WAITING_INT;
        internalBuffer.clear();
        intReader.reset();
    }
}