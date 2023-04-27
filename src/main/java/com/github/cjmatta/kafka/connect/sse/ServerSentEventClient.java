/**
 * Copyright © 2019 Christopher Matta (chris.matta@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.github.cjmatta.kafka.connect.sse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.sse.InboundSseEvent;
import javax.ws.rs.sse.SseEventSource;
import java.io.Closeable;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

public class ServerSentEventClient implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(ServerSentEventClient.class);

  private final Client client;
  private final WebTarget source;
  private final BlockingQueue<InboundSseEvent> queue;
  private SseEventSource sse;

  private volatile Throwable error;

  public ServerSentEventClient(String url) {
    log.debug("SSE Client initializing");
    this.client = ClientBuilder.newClient();
    this.source = client.target(url);
    queue = new LinkedBlockingDeque<>();
    log.debug("SSE Client initialized");
  }

  // New constructor for testing
  ServerSentEventClient(Client client, WebTarget source, SseEventSource sse) {
    this.client = client;
    this.source = source;
    this.queue = new LinkedBlockingDeque<>();
    this.sse = sse;
  }

  public void start() throws IOException {
    try {
      sse = SseEventSource
        .target(this.source)
        .reconnectingEvery(2, TimeUnit.SECONDS)
        .build();

      sse.register(this::onMessage, this::onError);
      log.debug("Opening SSE client.");
      sse.open();
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  public void stop() {
    log.debug("Closing SSE client.");
    this.sse.close();
  }

  @Override
  public void close() {
    client.close();
  }

  // New method for testing
  BlockingQueue<InboundSseEvent> getQueueForTesting() {
    return queue;
  }


  public List<InboundSseEvent> getRecords() throws InterruptedException {
    if (hasError()) {
      closeResources();
      throw new IllegalStateException("Error occurred while processing SSE events", error);
    }
    List<InboundSseEvent> records = new LinkedList<>();

    InboundSseEvent event = this.queue.poll(1L, TimeUnit.SECONDS);
    if (event == null) {
      log.debug("Queue was empty, returning empty list");
      return records;
    }

    if (event.getName() != null) {
      records.add(event);
    }

    this.queue.drainTo(records);
    log.debug("Returning " + records.size() + " records.");
    return records;
  }

  private void onMessage(InboundSseEvent event) {
    log.debug("got event with ID: " + event.getId());
    log.debug("got event with EVENT: " + event.getName());
    log.debug("got event with DATA: " + event.readData());
    this.queue.add(event);
  }

  private void onError(Throwable error) {
    log.error("Error while processing SSE event", error);
    this.error = error;
  }

  private boolean hasError() {
    return error != null;
  }

  private void closeResources() {
    log.debug("Closing resources due to error.");
    stop();
    close();
  }
}
