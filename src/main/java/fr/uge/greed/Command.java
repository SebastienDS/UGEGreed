package fr.uge.greed;

import java.nio.file.Path;

public sealed interface Command {
  record Info() implements Command {}
  record Start(String urlJar, String fullyQualifiedName, long startRange, long endRange, Path filename) implements Command {
    public Start {
      System.out.println(filename.toAbsolutePath());
    }
  }
  record Disconnect() implements Command {}
  record DisconnectNow() implements Command {}
  record TestTask(long start, long end) implements Command {}
  record TestSlowTask(long start, long end) implements Command {}
  record Test() implements Command {}
}