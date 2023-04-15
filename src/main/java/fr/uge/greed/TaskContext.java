package fr.uge.greed;

import fr.uge.greed.packet.RequestState;
import fr.uge.greed.packet.ResponseTask;
import fr.uge.greed.packet.Task;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public final class TaskContext {
  private static final Logger logger = Logger.getLogger(TaskContext.class.getName());

  private final Application server;
  private final long taskID;
  private final Command.Start task;
  private final Set<SocketAddress> taskMembers;
  private boolean tasksSent;
  private final HashMap<SocketAddress, Task> requestedTasks = new HashMap<>();

  public TaskContext(Application server, long taskID, Command.Start task, Set<SocketAddress> taskMember) {
    Objects.requireNonNull(server);
    Objects.requireNonNull(task);
    Objects.requireNonNull(taskMember);
    this.server = server;
    this.taskID = taskID;
    this.task = task;
    this.taskMembers = taskMember;
  }

  public void requestState() {
    var serverAddress = server.address();
    taskMembers.forEach(address -> server.states().put(address, null)); // reset state
    server.states().put(serverAddress, server.tasksInProgress()); // add my state
    server.broadcast(new Packet(new Header(new TransmissionMode.Broadcast(serverAddress), RequestState.OPCODE), new RequestState()), serverAddress);
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
        .map(server.states()::get)
        .filter(Objects::nonNull)
        .count();
    return received == taskMembers.size();
  }

  private void requestTasks() {
    var members = server.states().entrySet()
        .stream()
        .filter(entry -> taskMembers.contains(entry.getKey()))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    var tasksCount = Math.abs(task.endRange() - task.startRange()) + 1;
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
      var start = task.startRange() + taskIndex;
      var end = start + assignedTaskCount - 1;
      var newTask = new Task(taskID, task.urlJar(), task.fullyQualifiedName(), new Task.Range(start, end));
      taskIndex += assignedTaskCount;

      requestedTasks.put(entry.getKey(), newTask);
    }
    logger.info("Tasks created : " + requestedTasks);
  }

  private void sendTasks() {
    var serverAddress = server.address();
    requestedTasks.forEach((address, task) -> {
      if (address.equals(serverAddress)) { // no context for myself, nothing to send
        try {
          server.queueTask(serverAddress, task);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      } else {
        var packet = new Packet(new Header(new TransmissionMode.Transfer(serverAddress, address), Task.OPCODE), task);
        server.transfer(address, packet);
      }
    });
  }

  public void addResponse(SocketAddress source, ResponseTask response) throws IOException {
    Objects.requireNonNull(source);
    Objects.requireNonNull(response);
    logger.info("Task response : " + source + " - " + response);
    if (response.taskStatus() == ResponseTask.OK) {
      var content = response.response().orElseThrow();
      Files.writeString(task.filename(), content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }
  }

  public Command.Start task() {
    return task;
  }

  public Map<SocketAddress, Task> requestedTasks() {
    return requestedTasks;
  }

  public Set<SocketAddress> taskMembers() {
    return taskMembers;
  }
}