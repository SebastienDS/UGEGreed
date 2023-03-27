package fr.uge.greed;


import java.util.Objects;

public record Packet(Header header, Payload payload) {
  public Packet {
    Objects.requireNonNull(header);
    Objects.requireNonNull(payload);
  }
}
