package fr.uge.greed;

import fr.uge.greed.packet.http.HTTPResponse;
import fr.uge.greed.reader.HTTPReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.SelectionKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public final class HTTPContext implements Context<HTTPContext.Request> {
  public record Request(String host, String path, Consumer<Optional<HTTPResponse>> callback) {}

  private final Application server;
  private final BasicContext<Request, HTTPResponse> context;
  private final Request request;

  public HTTPContext(Application server, SelectionKey key, Request request) {
    Objects.requireNonNull(server);
    Objects.requireNonNull(request);
    this.server = server;
    context = new BasicContext<>(
        key,
        new HTTPReader(),
        this::onReceived,
        this::onConnection
    );
    this.request = request;
  }

  private void sendRequest() {
    context.send(request, request -> {
      var req = "GET " + request.path + " HTTP/1.1\r\nHost: " + request.host + "\r\n\r\n";
      var b = StandardCharsets.US_ASCII.encode(req);
      b.position(b.limit());
      return b;
    });
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

  private void onReceived(Optional<HTTPResponse> response) {
    try {
      processPacket(response);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void onConnection() {
    System.out.println("HTTP connected");
    sendRequest();
    System.out.println("Request sent");
  }

  private void processPacket(Optional<HTTPResponse> response) throws IOException {
    request.callback.accept(response);
    context.silentlyClose();
  }
}