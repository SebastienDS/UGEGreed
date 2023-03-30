package fr.uge.greed;

import fr.uge.greed.reader.PacketReader;
import fr.uge.greed.util.Helpers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.ArrayDeque;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Application {
  private class Context {
    private final SelectionKey key;
    private final SocketChannel sc;
    private final PacketReader reader = new PacketReader();
    private final ByteBuffer bufferIn = ByteBuffer.allocate(BUFFER_SIZE);
    private final ByteBuffer bufferOut = ByteBuffer.allocate(BUFFER_SIZE);
    private final ArrayDeque<ByteBuffer> queue = new ArrayDeque<>();
    private boolean closed = false;

    private Context(SelectionKey key) {
      this.key = key;
      this.sc = (SocketChannel) key.channel();
    }

    /**
     * Process the content of bufferIn
     * <p>
     * The convention is that bufferIn is in write-mode before the call to process and
     * after the call
     */
    private void processIn() {
      while (bufferIn.hasRemaining()) {
        switch (reader.process(bufferIn)) {
          case ERROR:
            silentlyClose();
          case REFILL:
            return;
          case DONE:
            // TODO
            reader.reset();
            break;
        }
      }
    }

    /**
     * Add a packet to the packet queue, tries to fill bufferOut and updateInterestOps
     *
     * @param packet the packet to add
     */
    public void queuePacket(Packet packet) {
      var buffer = packet.toByteBuffer();
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
      key.interestOps(interestOps);
    }

    private void silentlyClose() {
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
    private void doRead() throws IOException {
      if (sc.read(bufferIn) == -1) {
        logger.info("Connection closed by " + sc.getRemoteAddress());
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

    private void doWrite() throws IOException {
      bufferOut.flip();
      sc.write(bufferOut);
      bufferOut.compact();
      processOut();
      updateInterestOps();
    }

  }

  private static final int BUFFER_SIZE = 1_024;
  private static final Logger logger = Logger.getLogger(Application.class.getName());

  private final ServerSocketChannel serverSocketChannel;
  private final Selector selector;

  public Application(int port) throws IOException {
    serverSocketChannel = ServerSocketChannel.open();
    serverSocketChannel.bind(new InetSocketAddress(port));
    selector = Selector.open();
  }

  public void launch() throws IOException {
    serverSocketChannel.configureBlocking(false);
    serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
    while (!Thread.interrupted()) {
      Helpers.printKeys(selector); // for debug
      System.out.println("Starting select");
      try {
        selector.select(this::treatKey);
      } catch (UncheckedIOException tunneled) {
        throw tunneled.getCause();
      }
      System.out.println("Select finished");
    }
  }

  private void treatKey(SelectionKey key) {
    Helpers.printSelectedKey(key); // for debug
    try {
      if (key.isValid() && key.isAcceptable()) {
        doAccept();
      }
    } catch (IOException ioe) {
      // lambda call in select requires to tunnel IOException
      throw new UncheckedIOException(ioe);
    }
    try {
      if (key.isValid() && key.isWritable()) {
        ((Context) key.attachment()).doWrite();
      }
      if (key.isValid() && key.isReadable()) {
        ((Context) key.attachment()).doRead();
      }
    } catch (IOException e) {
      logger.log(Level.INFO, "Connection closed with client due to IOException");
      silentlyClose(key);
    }
  }

  private void doAccept() throws IOException {
    var client = serverSocketChannel.accept();
    if (client == null) {
      return;
    }
    client.configureBlocking(false);
    var selectionKey = client.register(selector, SelectionKey.OP_READ);
    selectionKey.attach(new Context(selectionKey));
  }

  private void silentlyClose(SelectionKey key) {
    Channel sc = key.channel();
    try {
      sc.close();
    } catch (IOException e) {
      // ignore exception
    }
  }


  public static void main(String[] args) throws NumberFormatException, IOException {
    if (args.length != 1) {
      usage();
      return;
    }
    new Application(Integer.parseInt(args[0])).launch();
  }

  private static void usage() {
    System.out.println("Usage : Application port");
  }
}