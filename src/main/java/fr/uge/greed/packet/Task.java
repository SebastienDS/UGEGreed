package fr.uge.greed.packet;

import fr.uge.greed.Payload;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public record Task(long id, String url, String className, Range range) implements Payload {
  public static final byte OPCODE = 6;

  public record Range(long from, long to) {
    public Range {
      if (from > to) {
        throw new IllegalArgumentException("From must be < To");
      }
    }
  }

  public Task {
    Objects.requireNonNull(url);
    Objects.requireNonNull(className);
    Objects.requireNonNull(range);
  }

  @Override
  public int getRequiredBytes() {
    return 3 * Long.BYTES + 2 * Integer.BYTES + 2048;
  }

  @Override
  public void encode(ByteBuffer buffer) {
    Objects.requireNonNull(buffer);
    var encodedUrl = StandardCharsets.UTF_8.encode(url);
    var encodedClassName = StandardCharsets.UTF_8.encode(className);
    buffer.putLong(id)
        .putInt(encodedUrl.remaining())
        .put(encodedUrl)
        .putInt(encodedClassName.remaining())
        .put(encodedClassName)
        .putLong(range.from())
        .putLong(range.to());
  }
}
