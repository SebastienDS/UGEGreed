package fr.uge.greed.packet.http;

import java.nio.ByteBuffer;
import java.util.Objects;

public record HTTPResponse(HTTPHeader header, ByteBuffer body) {
  public HTTPResponse {
    Objects.requireNonNull(header);
    Objects.requireNonNull(body);
  }
}
