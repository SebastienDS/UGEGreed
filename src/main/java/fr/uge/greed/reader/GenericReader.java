package fr.uge.greed.reader;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public class GenericReader<T> implements Reader<T> {
  enum State {
    DONE, WAITING, ERROR;
  }

  private final List<Reader<?>> readers;
  private final Function<List<Object>, T> mapper;
  private State state = State.WAITING;
  private int current = 0;
  private final ArrayList<Object> parts = new ArrayList<>();


  public GenericReader(List<Reader<?>> readers, Function<List<Object>, T> mapper) {
    Objects.requireNonNull(readers);
    Objects.requireNonNull(mapper);
    this.readers = List.copyOf(readers);
    this.mapper = mapper;
  }

  @Override
  public Reader.ProcessStatus process(ByteBuffer buffer) {
    Objects.requireNonNull(buffer);
    if (state == State.DONE || state == State.ERROR) {
      throw new IllegalStateException();
    }

    for (; current < readers.size(); current++) {
      var reader = readers.get(current);
      var status = reader.process(buffer);
      if (status == Reader.ProcessStatus.ERROR || status == Reader.ProcessStatus.REFILL) {
        return status;
      }

      parts.add(reader.get());
      reader.reset();
    }

    state = State.DONE;
    return Reader.ProcessStatus.DONE;
  }

  @Override
  public T get() {
    if (state != State.DONE) {
      throw new IllegalStateException();
    }
    return mapper.apply(parts);
  }

  @Override
  public void reset() {
    state = State.WAITING;
    readers.forEach(Reader::reset);
    parts.clear();
    current = 0;
  }
}