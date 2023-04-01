package fr.uge.greed;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Objects;

public final class SocketAddress {
  private final InetSocketAddress address;

  private SocketAddress(InetSocketAddress address) {
    this.address = address;
  }

  public SocketAddress(int port) {
    this(new InetSocketAddress(port));
  }

  public SocketAddress(String addressIP, int port) {
    this(new InetSocketAddress(addressIP, port));
  }

  public SocketAddress(byte[] bytes, int port) throws UnknownHostException {
    this(new InetSocketAddress(InetAddress.getByAddress(bytes), port));
  }

  public int version() {
    return bytes().length == 4 ? 4 : 16;
  }

  public byte[] bytes() {
    return address.getAddress().getAddress();
  }

  public int port() {
    return address.getPort();
  }

  public InetSocketAddress address() {
    return address;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof SocketAddress a && address.equals(a.address);
  }

  @Override
  public int hashCode() {
    return address.hashCode();
  }

  public int getRequiredBytes() {
    return Integer.BYTES + (bytes().length + 1) * Byte.BYTES;
  }

  public void encode(ByteBuffer buffer) {
    Objects.requireNonNull(buffer);
    buffer.put((byte) version());
    for (var value : bytes()) {
      buffer.put(value);
    }
    buffer.putInt(port());
  }
}
