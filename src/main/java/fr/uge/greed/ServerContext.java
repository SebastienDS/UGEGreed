package fr.uge.greed;

import fr.uge.greed.packet.*;
import fr.uge.greed.reader.PacketReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ServerContext implements Context<Packet> {
  private final Application server;
  private final BasicContext<Packet, Packet> context;

  public ServerContext(Application server, SelectionKey key, SocketAddress targetAddress, boolean isReconnection) {
    Objects.requireNonNull(server);
    this.server = server;
    context = new BasicContext<>(
        key, targetAddress,
        new PacketReader(),
        this::onReceived,
        isReconnection ? this::onReconnection : this::onConnection
    );
  }

  public ServerContext(Application server, SelectionKey key, SocketAddress targetAddress) {
    this(server, key, targetAddress, false);
  }

  public ServerContext(Application server, SelectionKey key) {
    this(server, key, null, false);
  }

  public void queuePacket(Packet packet) {
    context.send(packet, Packet::toByteBuffer);
  }

  @Override
  public void silentlyClose() {
    context.silentlyClose();
  }

  @Override
  public void doRead() throws IOException {
    context.doRead();
  }

  @Override
  public void doWrite() throws IOException {
    context.doWrite();
  }

  @Override
  public void doConnect() throws IOException {
    context.doConnect();
  }

  public SocketAddress address() {
    return context.address();
  }

  private void address(SocketAddress targetAddress) {
    context.address(targetAddress);
  }

  private void closeConnection() {
    context.closeConnection();
  }

  private void onReceived(Optional<Packet> packet) {
    try {
      processPacket(packet);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void onConnection() {
    queuePacket(new Packet(new Header(new TransmissionMode.Local(), Connection.OPCODE), new Connection(server.authentication(), server.address())));
  }

  private void onReconnection() {
    var subNetwork = Stream.concat(
        Stream.of(server.address()),
        server.siblings()
            .stream()
            .filter(address -> !address.equals(server.parentAddress()))
    ).toList();

    queuePacket(new Packet(new Header(new TransmissionMode.Local(), Reconnection.OPCODE), new Reconnection(server.authentication(), server.address(), subNetwork)));

    server.states().remove(server.parentAddress());
    server.parentAddress(server.rootAddress());
    System.out.println("Reconnected");
  }

  private void processPacket(Optional<Packet> packetOptional) throws IOException {
    if (packetOptional.isEmpty()) return;
    var packet = packetOptional.orElseThrow();

    var servers = server.servers();
    var serverAddress = server.address();
    var rootAddress = server.rootAddress();

    System.out.println(packet);

    switch(packet.payload()) {
      case Connection c -> {
        if (!server.authentication().equals(c.authentication())) {
          queuePacket(new Packet(new Header(new TransmissionMode.Local(), RejectConnection.OPCODE), new RejectConnection()));
          System.out.println("refuse connection");
          return;
        }

        context.address(c.address());
        server.siblings().add(c.address());
        var network = Stream.concat(
            Stream.of(rootAddress),
            servers.keySet().stream().filter(address -> !address.equals(rootAddress))
        ).toList();
        queuePacket(new Packet(new Header(new TransmissionMode.Local(), Validation.OPCODE), new Validation(network)));
        server.broadcast(new Packet(new Header(new TransmissionMode.Broadcast(context.address()), NewServer.OPCODE), new NewServer(context.address())), context.address());
        servers.put(context.address(), this);
      }
      case Validation v -> {
        System.out.println("Connected");

        v.addresses().forEach(a -> servers.put(a, this));
        server.rootAddress(v.addresses().get(0));
        servers.put(serverAddress, null);
      }
      case NewServer ns -> {
        if (ns.address().equals(serverAddress)) return;
        server.broadcast(packet, context.address());
        servers.put(ns.address(), this);
      }
      case RequestState r -> {
        server.broadcast(packet, context.address());
        var source = ((TransmissionMode.Broadcast) packet.header().mode()).source();
        var response = new Packet(new Header(new TransmissionMode.Transfer(serverAddress, source), ResponseState.OPCODE), new ResponseState(server.tasksInProgress()));
        server.transfer(source, response);
      }
      case ResponseState r -> {
        var mode = ((TransmissionMode.Transfer) packet.header().mode());
        if (!mode.destination().equals(serverAddress)) {
          server.transfer(mode.destination(), packet);
          return;
        }
        server.states().put(mode.source(), r.tasksInProgress());
        server.tasks().values().forEach(TaskContext::process);
      }
      case Task t -> {
        var mode = ((TransmissionMode.Transfer) packet.header().mode());
        if (!mode.destination().equals(serverAddress)) {
          server.transfer(mode.destination(), packet);
          return;
        }
        server.queueTask(mode.source(), t);
      }
      case ResponseTask r -> {
        var mode = ((TransmissionMode.Transfer) packet.header().mode());
        if (!mode.destination().equals(serverAddress)) {
          server.transfer(mode.destination(), packet);
          return;
        }
        server.tasks().get(r.taskId()).addResponse(mode.source(), r);
      }
      case AnnulationTask t -> {
        var mode = ((TransmissionMode.Transfer) packet.header().mode());
        if (!mode.destination().equals(serverAddress)) {
          server.transfer(mode.destination(), packet);
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
          var filename = server.tasks().get(assignedTask.id()).task().filename();
          server.startTask(new Command.Start(assignedTask.url(), assignedTask.className(), assignedTask.range().from(), assignedTask.range().to(), filename), members);
        }
      }
      case Disconnection d -> {
        server.broadcast(packet, context.address());
        var source = ((TransmissionMode.Broadcast) packet.header().mode()).source();

        var oldContext = servers.remove(source);
        oldContext.closeConnection();
        server.siblings().remove(source);
        server.states().clear();
        server.assignedTasks().keySet().removeIf(entry -> entry.getKey().equals(source));

        if (source.equals(server.parentAddress())) {
          System.out.println("trying to reconnect");
          ServerContext context;
          try {
            var parentSocketChannel = SocketChannel.open();
            server.parentSocketChannel(parentSocketChannel);
            parentSocketChannel.configureBlocking(false);
            var key = parentSocketChannel.register(server.selector(), SelectionKey.OP_CONNECT);
            context = new ServerContext(server, key, rootAddress, true);
            key.attach(context);
            parentSocketChannel.connect(rootAddress.address());
          } catch (IOException e) {
            throw new UncheckedIOException(e);
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
        if (!server.authentication().equals(r.authentication())) {
          queuePacket(new Packet(new Header(new TransmissionMode.Local(), RejectConnection.OPCODE), new RejectConnection()));
          return;
        }

        context.address(r.address());
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
          server.broadcast(new Packet(new Header(new TransmissionMode.Broadcast(address), NewServer.OPCODE), new NewServer(address)), serverAddress);
        });
      }
      case RejectConnection r -> {
        System.out.println("Connection rejected");
        silentlyClose();
        System.exit(1);
      }

      default -> throw new IllegalStateException("Unexpected value: " + packet.payload());
    }
  }

  private Task cancelRequestedTask(SocketAddress client, long taskID) {
    var context = server.tasks().get(taskID);
    if (context == null) return null;
    return context.requestedTasks().remove(client);
  }

  private void cancelAssignedTasks(SocketAddress client, long taskID) {
    var clientTask = Map.entry(client, taskID);
    var tasks = server.assignedTasks().get(clientTask);
    if (tasks == null) return;
    tasks.forEach(future -> future.cancel(true));
    server.assignedTasks().remove(clientTask);
  }
}