package fr.uge.greed;

import java.io.IOException;

public interface Context<T> {
  void silentlyClose();
  void doRead() throws IOException;
  void doWrite() throws IOException;
  void doConnect() throws IOException;
}
