package fr.uge.greed;

import fr.uge.greed.packet.AnnulationTask;
import fr.uge.greed.packet.Disconnection;
import fr.uge.greed.packet.ResponseTask;
import fr.uge.greed.packet.Task;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.channels.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.LongStream;


public final class Application {
  private static final Logger logger = Logger.getLogger(Application.class.getName());

  private static final String CHECKER_DIRECTORY = "checkers";

  private final ServerSocketChannel serverSocketChannel;
  private final SocketAddress serverAddress;
  private SocketChannel parentSocketChannel;
  private SocketAddress parentAddress;
  private SocketAddress rootAddress;
  private final Selector selector;
  private final ArrayBlockingQueue<Command> queue = new ArrayBlockingQueue<>(10);
  private final HashMap<SocketAddress, ServerContext> servers = new HashMap<>();
  private final Set<SocketAddress> siblings = new HashSet<>();
  private long taskID = 0;
  private final HashMap<Long, TaskContext> tasks = new HashMap<>();
  private final HashMap<SocketAddress, Integer> states = new HashMap<>();
  private final ExecutorService executorService = Executors.newFixedThreadPool(10);
  private final AtomicInteger tasksInProgress = new AtomicInteger();
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
    siblings.add(parentAddress);
    serverSocketChannel = ServerSocketChannel.open();
    serverSocketChannel.bind(serverAddress.address());
    parentSocketChannel = SocketChannel.open();
    selector = Selector.open();
  }

  private void launchHTTPRequest(HTTPContext.Request request) throws IOException {
    var channel = SocketChannel.open();
    channel.configureBlocking(false);
    var key = channel.register(selector, SelectionKey.OP_CONNECT);
    key.attach(new HTTPContext(this, key, request));
    channel.connect(new InetSocketAddress(request.host(), 80));
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
            sendCommand(new Command.Start(parts[1], parts[2], Long.parseLong(parts[3]), Long.parseLong(parts[4]), Path.of(parts[5])));
          }
          case "DISCONNECT" -> sendCommand(new Command.Disconnect());
          case "TEST_TASK" -> sendCommand(new Command.TestTask(Long.parseLong(parts[1]), Long.parseLong(parts[2])));
          case "TEST_SLOW_TASK" -> sendCommand(new Command.TestSlowTask(Long.parseLong(parts[1]), Long.parseLong(parts[2])));
          case "TEST" -> sendCommand(new Command.Test());
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

  private void processCommands() throws IOException, InterruptedException {
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
            sendCommand(new Command.Start("http://www-igm.univ-mlv.fr/~carayol/Factorizer.jar", "fr.uge.factors.Factorizer", cmd.start(), cmd.end(), Path.of("test_task.out")));
          }
          case Command.TestSlowTask cmd -> {
            sendCommand(new Command.Start("http://www-igm.univ-mlv.fr/~carayol/SlowChecker.jar", "fr.uge.slow.SlowChecker", cmd.start(), cmd.end(), Path.of("test_slow_task.out")));
          }
          case Command.Test cmd -> {
            System.out.println("Command Test");
          }
        }
      }
    }
  }

  private void info() {
    System.out.println("Parent : " + parentAddress);

    states.put(serverAddress, tasksInProgress.get()); // update my taskInProgress
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
        .map(entry -> "\t- " + entry.getKey() + " - " + entry.getValue().task())
        .collect(Collectors.joining("\n"));
    System.out.println("Tasks :\n" + content);

    content = assignedTasks.entrySet()
        .stream()
        .map(entry -> "\t- " + entry.getKey() + " - " + entry.getValue())
        .collect(Collectors.joining("\n"));
    System.out.println("Assigned Tasks :\n" + content);
  }

  public void startTask(Command.Start task, Set<SocketAddress> members) {
    var taskContext = new TaskContext(this, taskID, task, members);
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
      context.taskMembers().stream()
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

  public void launch() throws IOException, InterruptedException {
    serverSocketChannel.configureBlocking(false);
    serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

    if (parentSocketChannel != null) {
      parentSocketChannel.configureBlocking(false);
      var key = parentSocketChannel.register(selector, SelectionKey.OP_CONNECT);
      key.attach(new ServerContext(this, key, parentAddress));
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
        ((Context<?>) key.attachment()).doConnect();
      }
      if (key.isValid() && key.isWritable()) {
        ((Context<?>) key.attachment()).doWrite();
      }
      if (key.isValid() && key.isReadable()) {
        ((Context<?>) key.attachment()).doRead();
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
    selectionKey.attach(new ServerContext(this, selectionKey));
  }

  private void silentlyClose(SelectionKey key) {
    Channel sc = key.channel();
    try {
      sc.close();
    } catch (IOException e) {
      // ignore exception
    }
  }

  public void broadcast(Packet packet, SocketAddress withoutMe) {
    siblings.stream()
        .filter(address -> !address.equals(withoutMe))
        .map(servers::get)
        .forEach(context -> context.queuePacket(packet));
  }

  public void transfer(SocketAddress destination, Packet packet) {
    var context = servers.get(destination);
    if (context == null) return;
    context.queuePacket(packet);
  }

  public void queueTask(SocketAddress source, Task task) throws IOException {
    Objects.requireNonNull(source);
    Objects.requireNonNull(task);
    var url = new URL(task.url());
    var checker_path = Path.of(CHECKER_DIRECTORY, url.getHost(), url.getPath());

    if (Files.exists(checker_path)) {
      System.out.println("From files");
      launchTask(source, task, checker_path);
    } else {
      System.out.println("From http");
      var request = new HTTPContext.Request(url.getHost(), url.getPath(), (response) -> {
        if (response.isEmpty()) {
          sendResponse(source, new ResponseTask(task.id(), ResponseTask.DOWNLOAD_ERROR, Optional.empty()));
          return;
        }

        System.out.println("Response received, Launch tasks");
        try {
          checker_path.getParent().toFile().mkdirs();
          try (var stream = Files.newOutputStream(checker_path)) {
            stream.write(response.get().body().array());
          }
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }

        launchTask(source, task, checker_path);
      });
      launchHTTPRequest(request);
    }
  }

  private void launchTask(SocketAddress source, Task task, Path checker_path) {
    var clientTask = Map.entry(source, task.id());

    taskStartRemainingValues.put(clientTask, task.range().from());

    var checkerOptional = Client.checkerFromDisk(checker_path, task.className());
    if (checkerOptional.isEmpty()) {
      var response = new ResponseTask(task.id(), ResponseTask.DOWNLOAD_ERROR, Optional.empty());
      sendResponse(source, response);
      return;
    }

    var checker = checkerOptional.orElseThrow();

    tasksInProgress.getAndUpdate(x -> (int) (x + Math.abs(task.range().to() - task.range().from()) + 1));

    var submittedTasks = LongStream.rangeClosed(task.range().from(), task.range().to())
        .mapToObj(value -> executorService.submit(() -> {
          try {
            var result = checker.check(value);
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
          tasksInProgress.getAndDecrement();
        })).toList();

    assignedTasks.put(clientTask, submittedTasks);
  }

  private void sendResponse(SocketAddress source, ResponseTask response) {
    synchronized (responseQueue) {
      responseQueue.offer(Map.entry(source, response));
      selector.wakeup();
    }
  }

  private void processResponses() throws IOException {
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

  public SocketAddress address() {
    return serverAddress;
  }

  public SocketAddress rootAddress() {
    return rootAddress;
  }

  public void rootAddress(SocketAddress rootAddress) {
    Objects.requireNonNull(rootAddress);
    this.rootAddress = rootAddress;
  }

  public SocketAddress parentAddress() {
    return parentAddress;
  }

  public void parentAddress(SocketAddress parentAddress) {
    Objects.requireNonNull(parentAddress);
    this.parentAddress = parentAddress;
  }

  public Map<SocketAddress, ServerContext> servers() {
    return servers;
  }

  public int tasksInProgress() {
    return tasksInProgress.get();
  }

  public Map<SocketAddress, Integer> states() {
    return states;
  }

  public Map<Long, TaskContext> tasks() {
    return tasks;
  }

  public Map<Map.Entry<SocketAddress, Long>, List<? extends Future<?>>> assignedTasks() {
    return assignedTasks;
  }

  public void parentSocketChannel(SocketChannel channel) {
    Objects.requireNonNull(channel);
    parentSocketChannel = channel;
  }

  public Set<SocketAddress> siblings() {
    return siblings;
  }

  public Selector selector() {
    return selector;
  }

  public static void main(String[] args) throws NumberFormatException, IOException, InterruptedException {
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