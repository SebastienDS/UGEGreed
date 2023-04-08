package fr.uge.greed;

import fr.uge.greed.packet.*;
import fr.uge.greed.reader.PacketReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
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
    private SocketAddress targetAddress;


    private Context(SelectionKey key, SocketAddress targetAddress) {
      this.key = key;
      this.sc = (SocketChannel) key.channel();
      this.targetAddress = targetAddress;
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
          targetAddress = c.address();
          queuePacket(new Packet(new Header(new TransmissionMode.Local(), Validation.OPCODE), new Validation(servers.keySet().stream().toList())));
          broadcast(new Packet(new Header(new TransmissionMode.Broadcast(targetAddress), NewServer.OPCODE), new NewServer(targetAddress)), targetAddress);
          servers.put(targetAddress, this);
        }
        case Validation v -> {
          v.addresses().forEach(a -> servers.put(a, this));
          servers.put(serverAddress, null);
        }
        case NewServer ns -> {
          broadcast(packet, targetAddress);
          servers.put(ns.address(), this);
        }
        case RequestState r -> {
          broadcast(packet, targetAddress);
          var source = ((TransmissionMode.Broadcast) packet.header().mode()).source();
          // TODO task in progress
          var response = new Packet(new Header(new TransmissionMode.Transfer(serverAddress, source), ResponseState.OPCODE), new ResponseState(0));
          servers.get(source).queuePacket(response);
        }
        case ResponseState r -> {
          var mode = ((TransmissionMode.Transfer) packet.header().mode());
          if (!mode.destination().equals(serverAddress)) {
            servers.get(mode.destination()).queuePacket(packet);
            return;
          }
          states.put(mode.source(), r.tasksInProgress());
          tasks.values().forEach(TaskContext::process);
        }
        case Task t -> {
          var mode = ((TransmissionMode.Transfer) packet.header().mode());
          if (!mode.destination().equals(serverAddress)) {
            servers.get(mode.destination()).queuePacket(packet);
            return;
          }
          queueTask(mode.source(), t);
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
      queuePacket(new Packet(new Header(new TransmissionMode.Local(), Connection.OPCODE), new Connection(serverAddress)));
      System.out.println("Connected");
    }

    public SocketAddress address() {
      return targetAddress;
    }
  }

  private final class TaskContext {
    private final long taskID;
    private final Command.Start task;
    private final Set<SocketAddress> taskMember;
    private boolean tasksSent;
    private final HashMap<SocketAddress, Task> requestedTasks = new HashMap<>();

    public TaskContext(long taskID, Command.Start task, Set<SocketAddress> taskMember) {
      Objects.requireNonNull(task);
      Objects.requireNonNull(taskMember);
      this.taskID = taskID;
      this.task = task;
      this.taskMember = taskMember;
    }

    public void requestState() {
      taskMember.forEach(address -> states.put(address, null)); // reset state
      states.put(serverAddress, 0); // add my state
      // TODO set real task in progress
      broadcast(new Packet(new Header(new TransmissionMode.Broadcast(serverAddress), RequestState.OPCODE), new RequestState()), serverAddress);
    }

    public void process() {
      if (tasksSent || !canStart()) {
        return;
      }
      requestTasks();
      tasksSent = true;
    }

    private boolean canStart() {
      var received = taskMember.stream()
          .map(states::get)
          .filter(Objects::nonNull)
          .count();
      return received == taskMember.size();
    }

    private void requestTasks() {
      var members = states.entrySet()
          .stream()
          .filter(entry -> taskMember.contains(entry.getKey()))
          .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
      var tasksCount = Math.abs(task.endRange - task.startRange);
      var distribution = distributeTasks(tasksCount, members);
      createTasks(distribution);
      sendTasks();
    }

    private static Map<SocketAddress, Integer> distributeTasks(long numTasks, Map<SocketAddress, Integer> tasksInProgress) {
      var totalTasksInProgress = tasksInProgress.values().stream().mapToInt(Integer::intValue).sum();
      var idealTasksPerServer = (numTasks + totalTasksInProgress) / tasksInProgress.size();
      var remainingTasks = numTasks;
      var distribution = new HashMap<SocketAddress, Integer>();

      for (var entry : tasksInProgress.entrySet()) {
        var address = entry.getKey();
        var tasks = entry.getValue();
        var extraTasks = 0;
        if (tasks < idealTasksPerServer) {
          extraTasks = (int) Math.min(remainingTasks, idealTasksPerServer - tasks);
          remainingTasks -= extraTasks;
        }
        distribution.put(address, extraTasks);
      }
      if (remainingTasks > 0) {
        var address = distribution.keySet().stream().findAny().orElseThrow();
        distribution.merge(address, (int) remainingTasks, Integer::sum);
      }
      return distribution;
    }

    private void createTasks(Map<SocketAddress, Integer> distribution) {
      var taskIndex = 0L;
      for (var entry : distribution.entrySet()) {
        var assignedTaskCount = entry.getValue();
        var start = task.startRange + taskIndex;
        var end = start + assignedTaskCount;
        // TODO fix end1 == start2
        var newTask = new Task(taskID, task.urlJar, task.fullyQualifiedName, new Task.Range(start, end));
        taskIndex += assignedTaskCount;

        requestedTasks.put(entry.getKey(), newTask);
      }
      logger.info("Tasks created : " + requestedTasks);
    }

    private void sendTasks() {
      requestedTasks.forEach((address, task) -> {
        var context = servers.get(address);
        if (context == null) { // no context for myself, nothing to send
          queueTask(serverAddress, task);
        } else {
          var packet = new Packet(new Header(new TransmissionMode.Transfer(serverAddress, address), Task.OPCODE), task);
          context.queuePacket(packet);
        }
      });
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
  private long taskID = 0;
  private final HashMap<Long, TaskContext> tasks = new HashMap<>();
  private final HashMap<SocketAddress, Integer> states = new HashMap<>();


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
            System.out.println("Members :\n" + content);

            content = states.entrySet()
                .stream()
                .map(entry -> "\t- " + entry.getKey() + " - " + entry.getValue())
                .collect(Collectors.joining("\n"));
            System.out.println("States :\n" + content);
          }
          case Command.Start cmd -> {
            System.out.println("Command Start " + cmd);
            var task = new TaskContext(taskID, cmd, Set.copyOf(servers.keySet()));
            tasks.put(taskID, task);
            taskID++;
            task.requestState();
          }
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

  private void queueTask(SocketAddress source, Task task) {
    // TODO
    logger.info("task queued " + source + " " + task);
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