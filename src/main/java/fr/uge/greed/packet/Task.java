package fr.uge.greed.packet;

import fr.uge.greed.Payload;

import java.util.Objects;

public record Task(long id, String url, String className, Range range) implements Payload {
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
}
