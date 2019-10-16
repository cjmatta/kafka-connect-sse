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

import com.launchdarkly.eventsource.EventHandler;
import com.launchdarkly.eventsource.EventSource;
import com.launchdarkly.eventsource.MessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

public class ServerSentEventClient implements EventHandler {
  private static final Logger log = LoggerFactory.getLogger(ServerSentEventClient.class);

  private BlockingQueue<MessageEvent> queue;
  private EventSource eventSource;
  private URI uri;

  public ServerSentEventClient(String url) throws Exception {
    try {
      uri = new URI(url);
      EventSource.Builder builder = new EventSource.Builder(this, uri);
      eventSource = builder.build();
      queue = new LinkedBlockingDeque<>();

    } catch (URISyntaxException e) {
      throw new ConnectException("Bad URI: " + e.getMessage());
    }

  }

  public void start() throws IOException {
    try {
      eventSource.start();
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  public void stop() {
    log.debug("Closing SSE client.");
    this.eventSource.close();
  }

  public List<MessageEvent> getRecords() throws InterruptedException {
    List<MessageEvent> records = new LinkedList<>();

    MessageEvent event = this.queue.poll(1L, TimeUnit.SECONDS);
    if (event == null) {
      log.debug("Queue was empty, returning empty list");
      return records;
    }

    if (event.getData() != null) {
      records.add(event);
    }

    this.queue.drainTo(records);
    log.debug("Returning " + records.size() + " records.");
    return records;
  }

  @Override
  public void onOpen() throws Exception {
    log.debug("Event handler opened");

  }

  @Override
  public void onClosed() throws Exception {
    log.debug("Event handler closed");

  }

  @Override
  public void onMessage(String s, MessageEvent messageEvent) throws Exception {
    log.debug("Event: " + messageEvent.toString());
    queue.offer(messageEvent);
  }

  @Override
  public void onComment(String s) throws Exception {
    log.debug("Event handler comment: " + s);

  }

  @Override
  public void onError(Throwable t) {
    log.error(t.getMessage());
  }
}
