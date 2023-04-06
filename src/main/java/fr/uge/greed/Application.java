package fr.uge.greed;

import fr.uge.greed.packet.Connection;
import fr.uge.greed.packet.NewServer;
import fr.uge.greed.packet.Validation;
import fr.uge.greed.reader.PacketReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Objects;
import java.util.Scanner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public final class Application {
  private final class Context {
    private final SelectionKey key;
    private final SocketChannel sc;
    private final PacketReader reader = new PacketReader();
    private final ByteBuffer bufferIn = ByteBuffer.allocate(BUFFER_SIZE);
    private final ByteBuffer bufferOut = ByteBuffer.allocate(BUFFER_SIZE);
    private final ArrayDeque<ByteBuffer> queue = new ArrayDeque<>();
    private boolean closed = false;
    private SocketAddress address;


    private Context(SelectionKey key, SocketAddress address) {
      this.key = key;
      this.sc = (SocketChannel) key.channel();
      this.address = address;
    }

    private Context(SelectionKey key) {
      this(key, null);
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
            var packet = reader.get();
            processPacket(packet);
            logger.info("Receive " + packet);
            reader.reset();
            break;
        }
      }
    }

    private void processPacket(Packet packet) {
      switch(packet.payload()) {
        case Connection c -> {
          address = c.address();
          queuePacket(new Packet(new Header(new TransmissionMode.Local(), (byte) 1), new Validation(servers.keySet().stream().toList())));
          broadcast(new Packet(new Header(new TransmissionMode.Broadcast(address), (byte) 3), new NewServer(address)), address);
          servers.put(address, this);
        }
        case Validation v -> {
          v.addresses().forEach(a -> servers.put(a, this));
          servers.put(serverAddress, null);
        }
        case NewServer ns -> {
          broadcast(packet, address);
          servers.put(ns.address(), this);
        }

        default -> throw new IllegalStateException("Unexpected value: " + packet.payload());
      }
    }

    /**
     * Add a packet to the packet queue, tries to fill bufferOut and updateInterestOps
     *
     * @param packet the packet to add
     */
    public void queuePacket(Packet packet) {
      logger.info("Send " + packet);
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

    public void doConnect() throws IOException {
      if (!sc.finishConnect()) {
        return;
      }
      key.interestOps(SelectionKey.OP_READ);
      queuePacket(new Packet(new Header(new TransmissionMode.Local(), (byte) 0), new Connection(serverAddress)));
      System.out.println("Connected");
    }

    public SocketAddress address() {
      return address;
    }
  }

  private sealed interface Command {
    record Info() implements Command {}
    record Start(String urlJar, String fullyQualifiedName, long startRange, long endRange, String filename) implements Command {}
    record Disconnect() implements Command {}
  }

  private static final int BUFFER_SIZE = 1_024;
  private static final Logger logger = Logger.getLogger(Application.class.getName());

  private final ServerSocketChannel serverSocketChannel;
  private final SocketAddress serverAddress;
  private final SocketChannel parentSocketChannel;
  private final SocketAddress parentAddress;
  private final Selector selector;
  private final ArrayBlockingQueue<Command> queue = new ArrayBlockingQueue<>(10);
  private final HashMap<SocketAddress, Context> servers = new HashMap<>();

  public Application(int port) throws IOException {
    serverAddress = new SocketAddress(port);
    serverSocketChannel = ServerSocketChannel.open();
    serverSocketChannel.bind(serverAddress.address());
    parentSocketChannel = null;
    parentAddress = null;
    selector = Selector.open();
  }

  public Application(int port, SocketAddress parent) throws IOException {
    Objects.requireNonNull(parent);
    serverAddress = new SocketAddress(port);
    parentAddress = parent;
    serverSocketChannel = ServerSocketChannel.open();
    serverSocketChannel.bind(serverAddress.address());
    parentSocketChannel = SocketChannel.open();
    selector = Selector.open();
  }

  private void consoleRun() {
    try (var scanner = new Scanner(System.in)) {
      while (scanner.hasNextLine()) {
        var line = scanner.nextLine();
        var parts = line.split(" ");
        switch (parts[0].toUpperCase()) {
          case "INFO" -> sendCommand(new Command.Info());
          case "START" -> {
            if (parts.length != 6) {
              System.out.println("Invalid command");
              continue;
            }
            sendCommand(new Command.Start(parts[1], parts[2], Long.parseLong(parts[3]), Long.parseLong(parts[4]), parts[5]));
          }
          case "DISCONNECT" -> sendCommand(new Command.Disconnect());
          default -> System.out.println("Invalid command");
        }
      }
    } catch (InterruptedException e) {
      logger.info("Console thread has been interrupted");
    } finally {
      logger.info("Console thread stopping");
    }
  }

  private void sendCommand(Command command) throws InterruptedException {
    synchronized (queue) {
      queue.put(command);
      selector.wakeup();
    }
  }

  private void processCommands() {
    for (;;) {
      synchronized (queue) {
        var command = queue.poll();
        if (command == null) {
          return;
        }

        switch (command) {
          case Command.Info ignored -> {
            var content = servers.keySet()
                .stream()
                .map(address -> "\t- " + address)
                .collect(Collectors.joining("\n"));
            System.out.println("Info :\n" + content);
          }
          case Command.Start cmd -> System.out.println("Command Start " + cmd);
          case Command.Disconnect cmd -> System.out.println("Command Disconnect " + cmd);
        }
      }
    }
  }

  public void launch() throws IOException {
    serverSocketChannel.configureBlocking(false);
    serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

    if (parentSocketChannel != null) {
      parentSocketChannel.configureBlocking(false);
      var key = parentSocketChannel.register(selector, SelectionKey.OP_CONNECT);
      key.attach(new Context(key, parentAddress));
      parentSocketChannel.connect(parentAddress.address());
    }
    else {
      servers.put(serverAddress, null);
    }

    var console = Thread.ofPlatform().daemon().start(this::consoleRun);

    while (!Thread.interrupted()) {
      try {
        selector.select(this::treatKey);
        processCommands();
      } catch (UncheckedIOException tunneled) {
        throw tunneled.getCause();
      }
    }
  }

  private void treatKey(SelectionKey key) {
    try {
      if (key.isValid() && key.isAcceptable()) {
        doAccept();
      }
    } catch (IOException ioe) {
      // lambda call in select requires to tunnel IOException
      throw new UncheckedIOException(ioe);
    }
    try {
      if (key.isValid() && key.isConnectable()) {
        ((Context) key.attachment()).doConnect();
      }
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

  private void broadcast(Packet packet, SocketAddress withoutMe) {
    selector.keys()
        .stream()
        .filter(key -> key.channel() != serverSocketChannel)
        .map(key -> (Context)key.attachment())
        .filter(context -> !context.address().equals(withoutMe))
        .forEach(context -> context.queuePacket(packet));
  }

  public static void main(String[] args) throws NumberFormatException, IOException {
    if (args.length == 1) {
      new Application(Integer.parseInt(args[0])).launch();
    } else if (args.length == 3) {
      new Application(Integer.parseInt(args[0]), new SocketAddress(args[1], Integer.parseInt(args[2]))).launch();
    } else {
      usage();
    }
  }

  private static void usage() {
    System.out.println("""
      Usage :
        - Application port
        - Application port hostname_parent port_parent
      """);
  }
}