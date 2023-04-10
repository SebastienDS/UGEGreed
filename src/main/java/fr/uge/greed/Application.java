package fr.uge.greed;

import fr.uge.greed.packet.*;
import fr.uge.greed.reader.PacketReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;


public final class Application {
  private final class Context {
    private final SelectionKey key;
    private final SocketChannel sc;
    private final PacketReader reader = new PacketReader();
    private final ByteBuffer bufferIn = ByteBuffer.allocate(BUFFER_SIZE);
    private final ByteBuffer bufferOut = ByteBuffer.allocate(BUFFER_SIZE);
    private final ArrayDeque<ByteBuffer> queue = new ArrayDeque<>();
    private boolean closed = false;
    private boolean connectionClosed = false;
    private SocketAddress targetAddress;
    private final boolean isReconnection;


    private Context(SelectionKey key, SocketAddress targetAddress, boolean isReconnection) {
      this.key = key;
      this.sc = (SocketChannel) key.channel();
      this.targetAddress = targetAddress;
      this.isReconnection = isReconnection;
    }

    private Context(SelectionKey key, SocketAddress targetAddress) {
      this(key, targetAddress, false);
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
            // logger.info("Receive " + packet);
            reader.reset();
            break;
        }
      }
    }

    private void processPacket(Packet packet) {
      switch(packet.payload()) {
        case Connection c -> {
          targetAddress = c.address();
          var network = Stream.concat(
              Stream.of(rootAddress),
              servers.keySet().stream().filter(address -> !address.equals(rootAddress))
          ).toList();
          queuePacket(new Packet(new Header(new TransmissionMode.Local(), Validation.OPCODE), new Validation(network)));
          broadcast(new Packet(new Header(new TransmissionMode.Broadcast(targetAddress), NewServer.OPCODE), new NewServer(targetAddress)), targetAddress);
          servers.put(targetAddress, this);
        }
        case Validation v -> {
          v.addresses().forEach(a -> servers.put(a, this));
          rootAddress = v.addresses().get(0);
          servers.put(serverAddress, null);
        }
        case NewServer ns -> {
          if (ns.address().equals(serverAddress)) return;
          broadcast(packet, targetAddress);
          servers.put(ns.address(), this);
        }
        case RequestState r -> {
          broadcast(packet, targetAddress);
          var source = ((TransmissionMode.Broadcast) packet.header().mode()).source();
          var response = new Packet(new Header(new TransmissionMode.Transfer(serverAddress, source), ResponseState.OPCODE), new ResponseState(taskInProgress.get()));
          transfer(source, response);
        }
        case ResponseState r -> {
          var mode = ((TransmissionMode.Transfer) packet.header().mode());
          if (!mode.destination().equals(serverAddress)) {
            transfer(mode.destination(), packet);
            return;
          }
          states.put(mode.source(), r.tasksInProgress());
          tasks.values().forEach(TaskContext::process);
        }
        case Task t -> {
          var mode = ((TransmissionMode.Transfer) packet.header().mode());
          if (!mode.destination().equals(serverAddress)) {
            transfer(mode.destination(), packet);
            return;
          }
          queueTask(mode.source(), t);
        }
        case ResponseTask r -> {
          var mode = ((TransmissionMode.Transfer) packet.header().mode());
          if (!mode.destination().equals(serverAddress)) {
            transfer(mode.destination(), packet);
            return;
          }
          tasks.get(r.taskId()).addResponse(mode.source(), r);
        }
        case AnnulationTask t -> {
          var mode = ((TransmissionMode.Transfer) packet.header().mode());
          if (!mode.destination().equals(serverAddress)) {
            transfer(mode.destination(), packet);
            return;
          }
          if (t.status() == AnnulationTask.CANCEL_MY_TASK) {
            cancelAssignedTasks(mode.source(), t.id());
          } else {
            var assignedTask = cancelRequestedTask(mode.source(), t.id());
            if (assignedTask == null) return;
            var members = servers.keySet()
                .stream()
                .filter(address -> !address.equals(mode.source()))
                .collect(Collectors.toSet());
            // reassign task to others
            // TODO get right filename
            startTask(new Command.Start(assignedTask.url(), assignedTask.className(), assignedTask.range().from(), assignedTask.range().to(), ""), members);
          }
        }
        case Disconnection d -> {
          broadcast(packet, targetAddress);
          var source = ((TransmissionMode.Broadcast) packet.header().mode()).source();

          var oldContext = servers.remove(source);
          oldContext.closeConnection();
          states.clear();
          assignedTasks.keySet().removeIf(entry -> entry.getKey().equals(source));

          if (source.equals(parentAddress)) {
            System.out.println("trying to reconnect");
            Context context;
            try {
              parentSocketChannel = SocketChannel.open();
              parentSocketChannel.configureBlocking(false);
              var key = parentSocketChannel.register(selector, SelectionKey.OP_CONNECT);
              context = new Context(key, rootAddress, true);
              key.attach(context);
              parentSocketChannel.connect(rootAddress.address());
            } catch (IOException e) {
              throw new RuntimeException(e);
            }

            var oldRoutes = servers.entrySet()
                .stream()
                .filter(entry -> entry.getValue() != null)
                .filter(entry -> source.equals(entry.getValue().address()))
                .map(Map.Entry::getKey)
                .toList();
            oldRoutes.forEach(address -> servers.put(address, context));
          }
        }
        case Reconnection r -> {
          targetAddress = r.address();
          var network = Stream.concat(
              Stream.of(rootAddress),
              servers.keySet()
                  .stream()
                  .filter(address -> !address.equals(rootAddress))
                  .filter(address -> !r.addresses().contains(address))
          ).toList();
          queuePacket(new Packet(new Header(new TransmissionMode.Local(), Validation.OPCODE), new Validation(network)));

          r.addresses().forEach(address -> {
            if (!address.equals(serverAddress)) {
              servers.put(address, this);
            }
            broadcast(new Packet(new Header(new TransmissionMode.Broadcast(address), NewServer.OPCODE), new NewServer(address)), serverAddress);
          });
        }

        default -> throw new IllegalStateException("Unexpected value: " + packet.payload());
      }
    }

    private Task cancelRequestedTask(SocketAddress client, long taskID) {
      var context = tasks.get(taskID);
      if (context == null) return null;
      return context.requestedTasks.remove(client);
    }

    private void cancelAssignedTasks(SocketAddress client, long taskID) {
      var clientTask = Map.entry(client, taskID);
      var tasks = assignedTasks.get(clientTask);
      if (tasks == null) return;
      tasks.forEach(future -> future.cancel(true));
      assignedTasks.remove(clientTask);
    }

    /**
     * Add a packet to the packet queue, tries to fill bufferOut and updateInterestOps
     *
     * @param packet the packet to add
     */
    public void queuePacket(Packet packet) {
      if (connectionClosed) return;
      // logger.info("Send " + packet + " " + targetAddress);
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
      try {
        key.interestOps(interestOps);
      } catch (CancelledKeyException e) {
        e.printStackTrace();
        silentlyClose();
      }
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
      if (isReconnection) {
        var subNetwork = Stream.concat(
            Stream.of(serverAddress),
            selector.keys()
                .stream()
                .filter(key -> key.channel() != serverSocketChannel)
                .map(key -> ((Context)key.attachment()).address())
                .filter(address -> !address.equals(parentAddress))
        ).toList();

        queuePacket(new Packet(new Header(new TransmissionMode.Local(), Reconnection.OPCODE), new Reconnection(serverAddress, subNetwork)));

        states.remove(parentAddress);
        parentAddress = rootAddress;

        System.out.println("Reconnected");
      } else {
        queuePacket(new Packet(new Header(new TransmissionMode.Local(), Connection.OPCODE), new Connection(serverAddress)));
        System.out.println("Connected");
      }
    }

    public SocketAddress address() {
      return targetAddress;
    }

    public void closeConnection() {
      connectionClosed = true;
    }
  }

  private final class TaskContext {
    private final long taskID;
    private final Command.Start task;
    private final Set<SocketAddress> taskMembers;
    private boolean tasksSent;
    private final HashMap<SocketAddress, Task> requestedTasks = new HashMap<>();

    public TaskContext(long taskID, Command.Start task, Set<SocketAddress> taskMember) {
      Objects.requireNonNull(task);
      Objects.requireNonNull(taskMember);
      this.taskID = taskID;
      this.task = task;
      this.taskMembers = taskMember;
    }

    public void requestState() {
      taskMembers.forEach(address -> states.put(address, null)); // reset state
      states.put(serverAddress, taskInProgress.get()); // add my state
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
      var received = taskMembers.stream()
          .filter(taskMembers::contains)
          .map(states::get)
          .filter(Objects::nonNull)
          .count();
      return received == taskMembers.size();
    }

    private void requestTasks() {
      var members = states.entrySet()
          .stream()
          .filter(entry -> taskMembers.contains(entry.getKey()))
          .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
      var tasksCount = Math.abs(task.endRange - task.startRange) + 1;
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
        if (assignedTaskCount == 0) {
          continue;
        }
        var start = task.startRange + taskIndex;
        var end = start + assignedTaskCount - 1;
        var newTask = new Task(taskID, task.urlJar, task.fullyQualifiedName, new Task.Range(start, end));
        taskIndex += assignedTaskCount;

        requestedTasks.put(entry.getKey(), newTask);
      }
      logger.info("Tasks created : " + requestedTasks);
    }

    private void sendTasks() {
      requestedTasks.forEach((address, task) -> {
        if (address.equals(serverAddress)) { // no context for myself, nothing to send
          queueTask(serverAddress, task);
        } else {
          var packet = new Packet(new Header(new TransmissionMode.Transfer(serverAddress, address), Task.OPCODE), task);
          transfer(address, packet);
        }
      });
    }

    public void addResponse(SocketAddress source, ResponseTask response) {
      Objects.requireNonNull(source);
      Objects.requireNonNull(response);
      logger.info("Task response : " + source + " - " + response);
      // TODO
    }
  }


  private sealed interface Command {
    record Info() implements Command {}
    record Start(String urlJar, String fullyQualifiedName, long startRange, long endRange, String filename) implements Command {}
    record Disconnect() implements Command {}
    record DisconnectNow() implements Command {}
    record TestTask(long start, long end) implements Command {}
    record TestSlowTask(long start, long end) implements Command {}
  }

  private static final int BUFFER_SIZE = 1_024;
  private static final Logger logger = Logger.getLogger(Application.class.getName());

  private final ServerSocketChannel serverSocketChannel;
  private final SocketAddress serverAddress;
  private SocketChannel parentSocketChannel;
  private SocketAddress parentAddress;
  private SocketAddress rootAddress;
  private final Selector selector;
  private final ArrayBlockingQueue<Command> queue = new ArrayBlockingQueue<>(10);
  private final HashMap<SocketAddress, Context> servers = new HashMap<>();
  private long taskID = 0;
  private final HashMap<Long, TaskContext> tasks = new HashMap<>();
  private final HashMap<SocketAddress, Integer> states = new HashMap<>();
  private final ExecutorService executorService = Executors.newFixedThreadPool(10);
  private final AtomicInteger taskInProgress = new AtomicInteger();
  private final ArrayBlockingQueue<Map.Entry<SocketAddress, ResponseTask>> responseQueue = new ArrayBlockingQueue<>(10);
  private final ConcurrentHashMap<Map.Entry<SocketAddress, Long>, Long> taskStartRemainingValues = new ConcurrentHashMap<>();
  private final HashMap<Map.Entry<SocketAddress, Long>, List<? extends Future<?>>> assignedTasks = new HashMap<>();


  public Application(int port) throws IOException {
    serverAddress = new SocketAddress("localhost", port);
    serverSocketChannel = ServerSocketChannel.open();
    serverSocketChannel.bind(serverAddress.address());
    parentSocketChannel = null;
    parentAddress = null;
    rootAddress = serverAddress;
    selector = Selector.open();
  }

  public Application(int port, SocketAddress parent) throws IOException {
    Objects.requireNonNull(parent);
    serverAddress = new SocketAddress("localhost", port);
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
          case "TEST_TASK" -> sendCommand(new Command.TestTask(Long.parseLong(parts[1]), Long.parseLong(parts[2])));
          case "TEST_SLOW_TASK" -> sendCommand(new Command.TestSlowTask(Long.parseLong(parts[1]), Long.parseLong(parts[2])));
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
          case Command.Info ignored -> info();
          case Command.Start cmd -> {
            System.out.println("Command Start " + cmd);
            startTask(cmd, Set.copyOf(servers.keySet()));
          }
          case Command.Disconnect cmd -> {
            System.out.println("Command Disconnect " + cmd);
            if (parentAddress == null) return;
            disconnect();
          }
          case Command.DisconnectNow ignored -> {
            if (parentAddress == null) return;
            closeConnections();
            System.exit(0);
          }
          case Command.TestTask cmd -> {
            try {
              sendCommand(new Command.Start("http://www-igm.univ-mlv.fr/~carayol/Factorizer.jar", "fr.uge.factors.Factorizer", cmd.start, cmd.end, "filename"));
            } catch (InterruptedException e) {
              throw new AssertionError(e);
            }
          }
          case Command.TestSlowTask cmd -> {
            try {
              sendCommand(new Command.Start("http://www-igm.univ-mlv.fr/~carayol/SlowChecker.jar", "fr.uge.slow.SlowChecker", cmd.start, cmd.end, "filename"));
            } catch (InterruptedException e) {
              throw new AssertionError(e);
            }
          }
        }
      }
    }
  }

  private void info() {
    System.out.println("Parent : " + parentAddress);

    states.put(serverAddress, taskInProgress.get()); // update my taskInProgress
    var content = servers.entrySet()
        .stream()
        .map(entry -> "\t- " + entry.getKey() + " - " + (entry.getValue() == null ? null : entry.getValue().address()))
        .collect(Collectors.joining("\n"));
    System.out.println("Members :\n" + content);

    content = states.entrySet()
        .stream()
        .map(entry -> "\t- " + entry.getKey() + " - " + entry.getValue())
        .collect(Collectors.joining("\n"));
    System.out.println("States :\n" + content);

    content = tasks.entrySet()
        .stream()
        .map(entry -> "\t- " + entry.getKey() + " - " + entry.getValue().task)
        .collect(Collectors.joining("\n"));
    System.out.println("Tasks :\n" + content);

    content = assignedTasks.entrySet()
        .stream()
        .map(entry -> "\t- " + entry.getKey() + " - " + entry.getValue())
        .collect(Collectors.joining("\n"));
    System.out.println("Assigned Tasks :\n" + content);
  }

  private void startTask(Command.Start task, Set<SocketAddress> members) {
    var taskContext = new TaskContext(taskID, task, members);
    tasks.put(taskID, taskContext);
    taskID++;
    taskContext.requestState();
    taskContext.process();
  }

  private void disconnect() {
    sendAnnulationForMyTasks();
    sendAnnulationForMyAssignedTasks();
    sendDisconnection();
    cancelTasks();
    Thread.ofPlatform().start(() -> {
      try {
        Thread.sleep(1000);
        sendCommand(new Command.DisconnectNow());
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
    });
  }

  private void closeConnections() {
    servers.values()
        .stream()
        .filter(Objects::nonNull)
        .forEach(Context::silentlyClose);
  }

  private void cancelTasks() {
    executorService.shutdown();
    try {
      if (!executorService.awaitTermination(800, TimeUnit.MILLISECONDS)) {
        executorService.shutdownNow();
      }
    } catch (InterruptedException e) {
      executorService.shutdownNow();
    }
  }

  private void sendAnnulationForMyTasks() {
    tasks.forEach((id, context) -> { // taskMembers have to cancel my task
      context.taskMembers.stream()
          .filter(address -> !address.equals(serverAddress))
          .forEach(address -> {
            var packet = new Packet(new Header(new TransmissionMode.Transfer(serverAddress, address), AnnulationTask.OPCODE), new AnnulationTask(id, AnnulationTask.CANCEL_MY_TASK, 0));
            transfer(address, packet);
          });
    });
  }

  private void sendAnnulationForMyAssignedTasks() {
    taskStartRemainingValues.forEach((entry, startRemainingValues) -> {
      var address = entry.getKey();
      var id = entry.getValue();

      if (address.equals(serverAddress)) return;

      var packet = new Packet(new Header(new TransmissionMode.Transfer(serverAddress, address), AnnulationTask.OPCODE), new AnnulationTask(id, AnnulationTask.CANCEL_ASSIGNED_TASK, startRemainingValues));
      transfer(address, packet);
    });
  }

  private void sendDisconnection() {
    var packet = new Packet(new Header(new TransmissionMode.Broadcast(serverAddress), Disconnection.OPCODE), new Disconnection());
    broadcast(packet, serverAddress);
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
        processResponses();
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
        .filter(context -> context.address() != null)
        .filter(context -> !context.address().equals(withoutMe))
        .forEach(context -> context.queuePacket(packet));
  }

  private void transfer(SocketAddress destination, Packet packet) {
    var context = servers.get(destination);
    if (context == null) return;
    context.queuePacket(packet);
  }

  private void queueTask(SocketAddress source, Task task) {
    // logger.info("task queued " + source + " " + task);
    var clientTask = Map.entry(source, task.id());

    taskStartRemainingValues.put(clientTask, task.range().from());

    var checkerOptional = Client.checkerFromHTTP(task.url(),task.className());
    if (checkerOptional.isEmpty()) {
      var response = new ResponseTask(task.id(), ResponseTask.DOWNLOAD_ERROR, Optional.empty());
      sendResponse(source, response);
      return;
    }

    var checker = checkerOptional.orElseThrow();
    taskInProgress.getAndUpdate(x -> (int) (x + Math.abs(task.range().to() - task.range().from()) + 1));

    var submittedTasks = LongStream.rangeClosed(task.range().from(), task.range().to())
        .mapToObj(value -> executorService.submit(() -> {
          try {
            String result;
            synchronized (checker) {
              result = checker.check(value);
            }
            var response = new ResponseTask(task.id(), ResponseTask.OK, Optional.of(result));
            sendResponse(source, response);
          } catch (InterruptedException e) {
            var response = new ResponseTask(task.id(), ResponseTask.TIMEOUT, Optional.empty());
            sendResponse(source, response);
          } catch (Exception e) {
            var response = new ResponseTask(task.id(), ResponseTask.EXCEPTION_THROWN, Optional.empty());
            sendResponse(source, response);
          }

          taskStartRemainingValues.merge(clientTask, value, (a, b) -> b > a ? b : a);
          taskInProgress.getAndDecrement();
        })).toList();

    assignedTasks.put(clientTask, submittedTasks);
  }

  private void sendResponse(SocketAddress source, ResponseTask response) {
    synchronized (responseQueue) {
      responseQueue.offer(Map.entry(source, response));
      selector.wakeup();
    }
  }

  private void processResponses() {
    for (;;) {
      synchronized (responseQueue) {
        var entry = responseQueue.poll();
        if (entry == null) {
          return;
        }

        var source = entry.getKey();
        var response = entry.getValue();

        if (source.equals(serverAddress)) {
          tasks.get(response.taskId()).addResponse(serverAddress, response);
        } else {
          var packet = new Packet(new Header(new TransmissionMode.Transfer(serverAddress, source), ResponseTask.OPCODE), response);
          transfer(source, packet);
        }
      }
    }
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