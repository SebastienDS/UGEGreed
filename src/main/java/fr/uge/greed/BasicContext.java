package fr.uge.greed;

import fr.uge.greed.reader.Reader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

public final class BasicContext<T, U> implements Context<T> {
  private static final int BUFFER_SIZE = 1_024;

  private final SelectionKey key;
  private final SocketChannel sc;
  private final ByteBuffer bufferIn = ByteBuffer.allocate(BUFFER_SIZE);
  private final ByteBuffer bufferOut = ByteBuffer.allocate(BUFFER_SIZE);
  private final ArrayDeque<ByteBuffer> queue = new ArrayDeque<>();
  private boolean closed = false;
  private boolean connectionClosed = false;
  private SocketAddress targetAddress;
  private final Reader<U> reader;
  private final Consumer<Optional<U>> onReceive;
  private final Runnable onConnection;


  public BasicContext(SelectionKey key, SocketAddress targetAddress, Reader<U> reader, Consumer<Optional<U>> onReceive, Runnable onConnection) {
    Objects.requireNonNull(key);
    Objects.requireNonNull(reader);
    Objects.requireNonNull(onReceive);
    Objects.requireNonNull(onConnection);
    this.key = key;
    this.sc = (SocketChannel) key.channel();
    this.targetAddress = targetAddress;
    this.reader = reader;
    this.onReceive = onReceive;
    this.onConnection = onConnection;
  }

  public BasicContext(SelectionKey key, Reader<U> reader, Consumer<Optional<U>> onReceive, Runnable onConnection) {
    this(key, null, reader, onReceive, onConnection);
  }

  /**
   * Process the content of bufferIn
   * <p>
   * The convention is that bufferIn is in write-mode before the call to process and
   * after the call
   */
  private void processIn() {
    while (bufferIn.position() != 0) {
      switch (reader.process(bufferIn)) {
        case ERROR:
          onReceive.accept(Optional.empty());
          silentlyClose();
        case REFILL:
          return;
        case DONE:
          var response = reader.get();
          onReceive.accept(Optional.of(response));
          reader.reset();
          break;
      }
    }
  }

  /**
   * Add a content to the content queue, tries to fill bufferOut and updateInterestOps
   *
   * @param content the content to add
   */
  public void send(T content, Function<T, ByteBuffer> mapper) {
    Objects.requireNonNull(content);
    Objects.requireNonNull(mapper);
    if (connectionClosed) return;
    var buffer = mapper.apply(content);
    queue.offer(buffer.flip());
    processOut();
    updateInterestOps();
  }

  /**
   * Try to fill bufferOut from the packet queue
   */
  private void processOut() {
    while (!queue.isEmpty() && bufferOut.hasRemaining()) {
      var packet = queue.peek();
      if (!packet.hasRemaining()) {
        queue.poll();
        continue;
      }
      if (packet.remaining() <= bufferOut.remaining()) {
        bufferOut.put(packet);
      } else {
        var oldLimit = packet.limit();
        packet.limit(bufferOut.remaining());
        bufferOut.put(packet);
        packet.limit(oldLimit);
      }
    }
  }

  /**
   * Update the interestOps of the key looking only at values of the boolean
   * closed and of both ByteBuffers.
   * <p>
   * The convention is that both buffers are in write-mode before the call to
   * updateInterestOps and after the call. Also it is assumed that process has
   * been be called just before updateInterestOps.
   */
  private void updateInterestOps() {
    int interestOps = 0;
    if (!closed && bufferIn.hasRemaining()) {
      interestOps |= SelectionKey.OP_READ;
    }
    if (bufferOut.position() != 0) {
      interestOps |= SelectionKey.OP_WRITE;
    }

    if (interestOps == 0) {
      silentlyClose();
      return;
    }
    try {
      key.interestOps(interestOps);
    } catch (CancelledKeyException e) {
      silentlyClose();
    }
  }

  @Override
  public void silentlyClose() {
    closeConnection();
    try {
      sc.close();
    } catch (IOException e) {
      // ignore exception
    }
  }

  /**
   * Performs the read action on sc
   * <p>
   * The convention is that both buffers are in write-mode before the call to
   * doRead and after the call
   *
   * @throws java.io.IOException if the read fails
   */
  @Override
  public void doRead() throws IOException {
    if (sc.read(bufferIn) == -1) {
      closed = true;
    }
    processIn();
    updateInterestOps();
  }

  /**
   * Performs the write action on sc
   * <p>
   * The convention is that both buffers are in write-mode before the call to
   * doWrite and after the call
   *
   * @throws java.io.IOException if the write fails
   */
  @Override
  public void doWrite() throws IOException {
    bufferOut.flip();
    sc.write(bufferOut);
    bufferOut.compact();
    processOut();
    updateInterestOps();
  }

  @Override
  public void doConnect() throws IOException {
    if (!sc.finishConnect()) {
      return;
    }
    key.interestOps(SelectionKey.OP_READ);
    onConnection.run();
  }

  public SocketAddress address() {
    return targetAddress;
  }

  public void address(SocketAddress targetAddress) {
    Objects.requireNonNull(targetAddress);
    this.targetAddress = targetAddress;
  }

  public void closeConnection() {
    connectionClosed = true;
  }
}