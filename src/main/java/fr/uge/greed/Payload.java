package fr.uge.greed;

import java.nio.ByteBuffer;

public interface Payload {
    int getRequiredBytes();
    void encode(ByteBuffer buffer);
}