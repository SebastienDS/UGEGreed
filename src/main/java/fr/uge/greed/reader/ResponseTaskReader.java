package fr.uge.greed.reader;

import fr.uge.greed.packet.ResponseTask;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;

public class ResponseTaskReader implements Reader<ResponseTask> {
  private enum State {
    DONE, WAITING_TASK_ID, WAITING_STATUS, WAITING_OPTIONAL_STRING, ERROR
  };

  private State state = State.WAITING_TASK_ID;
  private final LongReader longReader = new LongReader();
  private final ByteReader byteReader = new ByteReader();
  private final StringReader stringReader = new StringReader();
  private long taskId;
  private Byte taskStatus;
  private String response;


  @Override
  public ProcessStatus process(ByteBuffer buffer) {
    Objects.requireNonNull(buffer);
    if (state == State.DONE || state == State.ERROR) {
      throw new IllegalStateException();
    }
    if (state == State.WAITING_TASK_ID) {
      processTaskId(buffer);
      if (state == State.ERROR) {
        reset();
        return ProcessStatus.ERROR;
      }
      if (state == State.WAITING_TASK_ID) {
        return ProcessStatus.REFILL;
      }
    }
    if (state == State.WAITING_STATUS) {
      processTaskStatus(buffer);
      if (state == State.ERROR) {
        reset();
        return ProcessStatus.ERROR;
      }
      if (state == State.WAITING_STATUS) {
        return ProcessStatus.REFILL;
      }
      if (state == State.DONE) {
        return ProcessStatus.DONE;
      }
    }
    processResponse(buffer);
    if (state == State.ERROR) {
      reset();
      return ProcessStatus.ERROR;
    }
    if (state != State.DONE) {
      return ProcessStatus.REFILL;
    }
    return ProcessStatus.DONE;
  }

  private void processTaskId(ByteBuffer buffer) {
    var status = longReader.process(buffer);
    switch (status) {
      case DONE -> {
        taskId = longReader.get();
        longReader.reset();
        state = State.WAITING_STATUS;
      }
      case ERROR -> state = State.ERROR;
    }
  }

  private void processTaskStatus(ByteBuffer buffer) {
    var status = byteReader.process(buffer);
    switch(status) {
      case DONE -> {
        taskStatus = byteReader.get();
        if (taskStatus >= 4 || taskStatus < 0) {
          state = State.ERROR;
          return ;
        }
        byteReader.reset();
        if (taskStatus == 0) {
          state = State.WAITING_OPTIONAL_STRING;
          return;
        }
        state = State.DONE;
      }
      case ERROR -> state = State.ERROR;
    }
  }

  private void processResponse(ByteBuffer buffer) {
    var status = stringReader.process(buffer);
    switch (status) {
      case DONE -> {
        response = stringReader.get();
        stringReader.reset();
        state = State.DONE;
      }
      case ERROR -> state = State.ERROR;
    }
  }

  @Override
  public ResponseTask get() {
    if (state != State.DONE) {
      throw new IllegalStateException();
    }
    return new ResponseTask(taskId, taskStatus, Optional.ofNullable(response));
  }

  @Override
  public void reset() {
    state = State.WAITING_TASK_ID;
    longReader.reset();
    byteReader.reset();
    stringReader.reset();
    response = null;
  }
}