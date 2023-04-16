package fr.uge.greed.reader;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

public final class GenericReader {
  interface Builder {
    <E> Supplier<E> add(Reader<E> reader);
  }

  private GenericReader() {}

  public static <T> Reader<T> create(Function<Builder, Supplier<T>> mapper) {
    Objects.requireNonNull(mapper);
    var readers = new ArrayList<Reader<?>>();
    var parts = new ArrayList<>();

    var supplier = mapper.apply(new Builder() {
      @Override
      @SuppressWarnings("unchecked")
      public <E> Supplier<E> add(Reader<E> reader) {
        Objects.requireNonNull(reader);
        var index = readers.size();
        readers.add(reader);
        return () -> (E)parts.get(index);
      }
    });

    Objects.requireNonNull(supplier);
    return new Reader<>() {
      enum State {
        DONE, WAITING, ERROR;
      }

      private State state = State.WAITING;
      private int current = 0;

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
        return supplier.get();
      }

      @Override
      public void reset() {
        state = State.WAITING;
        readers.forEach(Reader::reset);
        parts.clear();
        current = 0;
      }
    };
  }
}
