/**
 * Copyright © 2019 Christopher Matta (chris.matta@gmail.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
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
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.sse.InboundSseEvent;
import javax.ws.rs.sse.SseEventSource;
import java.io.Closeable;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client for Server Sent Events that connects to an SSE source and processes events.
 * Handles authentication, connection lifecycle, and event queuing.
 */
public class ServerSentEventClient implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(ServerSentEventClient.class);

  /**
   * Enum to track the connection state of the SSE client.
   * This helps in diagnosing connection issues and understanding the lifecycle.
   */
  public enum ConnectionState {
    INITIALIZED,    // Client is created but not yet connected
    CONNECTING,     // Connection attempt in progress
    CONNECTED,      // Successfully connected to SSE source
    DISCONNECTED,   // Gracefully disconnected
    FAILED          // Connection failed or encountered an error
  }
  
  private final Client client;
  private final WebTarget source;
  private final BlockingQueue<InboundSseEvent> queue;
  private SseEventSource sse;
  
  // Store username and password for authentication if provided
  private final String username;
  private final String password;
  
  // Track current connection state
  private volatile ConnectionState connectionState;
  // Store the last time an event was received
  private volatile long lastEventTimestamp;
  // Track event statistics
  private final AtomicLong totalEventsReceived = new AtomicLong(0);
  
  private volatile Throwable error;

  /**
   * Creates a new SSE client for the given URL without authentication.
   *
   * @param url The URL of the SSE stream
   */
  public ServerSentEventClient(String url) {
    log.info("Initializing SSE Client for URL: {}", url);
    this.client = ClientBuilder.newClient();
    this.source = client.target(url);
    this.username = null;
    this.password = null;
    this.queue = new LinkedBlockingDeque<>();
    this.connectionState = ConnectionState.INITIALIZED;
    this.lastEventTimestamp = System.currentTimeMillis();
    log.info("SSE Client initialized in state: {}", connectionState);
  }

  /**
   * Creates a new SSE client for the given URL with basic authentication.
   *
   * @param url      The URL of the SSE stream
   * @param username Username for basic authentication
   * @param password Password for basic authentication
   */
  ServerSentEventClient(String url, String username, String password) {
    log.info("Initializing SSE Client for URL: {} with authentication", url);
    this.client = ClientBuilder.newClient();
    this.source = client.target(url);
    this.username = username;
    this.password = password;
    this.queue = new LinkedBlockingDeque<>();
    this.connectionState = ConnectionState.INITIALIZED;
    this.lastEventTimestamp = System.currentTimeMillis();
    log.info("SSE Client initialized in state: {}", connectionState);
  }

  /**
   * Constructor for testing purposes.
   */
  ServerSentEventClient(Client client, WebTarget source, SseEventSource sse, String username, String password) {
    log.info("Initializing SSE Client for testing");
    this.client = client;
    this.source = source;
    this.username = username;
    this.password = password;
    this.queue = new LinkedBlockingDeque<>();
    this.sse = sse;
    this.connectionState = ConnectionState.INITIALIZED;
    this.lastEventTimestamp = System.currentTimeMillis();
    log.info("SSE Client initialized in state: {}", connectionState);
  }

  /**
   * Starts the SSE client connection to the source.
   * Registers handlers for events and errors.
   *
   * @throws IOException if an error occurs during connection setup
   */
  public void start() throws IOException {
    try {
      log.info("Starting SSE client connection to {}", source.getUri());
      setConnectionState(ConnectionState.CONNECTING);
      
      Invocation.Builder builder = this.source.request();
      
      // Apply basic authentication if credentials are provided
      if (username != null && password != null) {
        String auth = username + ":" + password;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
        String authHeader = "Basic " + encodedAuth;
        builder.header("Authorization", authHeader);
        log.debug("Added Basic Authentication header");
      }
      
      log.debug("Configuring SSE event source with reconnection interval of 2 seconds");
      sse = SseEventSource
        .target(this.source)
        .reconnectingEvery(2, TimeUnit.SECONDS)
        .build();

      sse.register(this::onMessage, this::onError);
      log.info("Opening SSE client connection...");
      sse.open();
      setConnectionState(ConnectionState.CONNECTED);
      log.info("SSE client successfully connected to {}", source.getUri());
    } catch (Exception e) {
      setConnectionState(ConnectionState.FAILED);
      log.error("Failed to start SSE client connection: {}", e.getMessage(), e);
      throw new IOException("Failed to establish SSE connection", e);
    }
  }

  /**
   * Stops the SSE client connection.
   */
  public void stop() {
    log.info("Stopping SSE client connection");
    if (this.sse != null) {
      this.sse.close();
      setConnectionState(ConnectionState.DISCONNECTED);
      log.info("SSE client connection closed");
    } else {
      log.warn("Attempted to stop SSE client, but no active connection exists");
    }
  }

  @Override
  public void close() {
    log.debug("Closing SSE client resources");
    if (client != null) {
      client.close();
      log.debug("SSE client resources closed");
    }
  }

  /**
   * Updates the connection state and logs the transition.
   * 
   * @param newState The new connection state
   */
  private void setConnectionState(ConnectionState newState) {
    ConnectionState oldState = this.connectionState;
    this.connectionState = newState;
    log.info("SSE client connection state changed: {} -> {}", oldState, newState);
  }

  /**
   * Returns the current connection state.
   *
   * @return The current connection state
   */
  public ConnectionState getConnectionState() {
    return connectionState;
  }

  /**
   * Returns the time (in milliseconds) since the last event was received.
   *
   * @return Time in milliseconds since last event
   */
  public long getTimeSinceLastEvent() {
    return System.currentTimeMillis() - lastEventTimestamp;
  }

  // Method for testing
  BlockingQueue<InboundSseEvent> getQueueForTesting() {
    return queue;
  }


  public List<InboundSseEvent> getRecords() throws InterruptedException {
    // Check connection state and log diagnostic information
    if (connectionState != ConnectionState.CONNECTED) {
      log.warn("Attempting to get records while connection state is: {}", connectionState);
    }
    
    // Log time since last event if it's been a while
    long timeSinceLastEvent = getTimeSinceLastEvent();
    if (timeSinceLastEvent > 30000) { // 30 seconds
      log.warn("No events received in the last {} milliseconds, connection may be stalled", timeSinceLastEvent);
    }
    
    if (hasError()) {
      log.error("Error detected in SSE client, closing resources before propagating error");
      closeResources();
      throw new IllegalStateException("Error occurred while processing SSE events", error);
    }
    
    List<InboundSseEvent> records = new LinkedList<>();

    log.debug("Polling for events with 1 second timeout. Queue size: {}", queue.size());
    InboundSseEvent event = this.queue.poll(1L, TimeUnit.SECONDS);
    if (event == null) {
      if (log.isDebugEnabled()) {
        log.debug("Queue was empty after polling, returning empty list. Connection state: {}", connectionState);
      }
      return records;
    }

    if (event.getName() != null) {
      if (log.isDebugEnabled()) {
        log.debug("Adding event to records - ID: {}, Name: {}", event.getId(), event.getName());
      }
      records.add(event);
    } else {
      log.warn("Received event with null name, skipping. Event ID: {}", event.getId());
    }

    int drained = this.queue.drainTo(records);
    log.debug("Drained {} additional events from queue. Total records to return: {}", drained, records.size());
    
    if (records.size() > 0) {
      log.info("Returning {} records. First event ID: {}, Last event ID: {}", 
              records.size(), 
              records.get(0).getId(), 
              records.get(records.size() - 1).getId());
    }
    
    return records;
  }

  /**
   * Processes an incoming SSE event.
   * Updates statistics and adds the event to the processing queue.
   *
   * @param event The incoming SSE event
   */
  private void onMessage(InboundSseEvent event) {
    lastEventTimestamp = System.currentTimeMillis();
    totalEventsReceived.incrementAndGet();
    
    if (log.isDebugEnabled()) {
      log.debug("Received SSE event - ID: {}, Name: {}, Data length: {}", 
                event.getId(), 
                event.getName(), 
                event.readData() != null ? event.readData().length() : 0);
    }
    
    if (totalEventsReceived.get() % 100 == 0) {
      log.info("Processed {} events total, current queue size: {}", 
               totalEventsReceived.get(), queue.size());
    }
    
    this.queue.add(event);
  }

  /**
   * Handles errors from the SSE connection.
   * Updates connection state and stores the error.
   *
   * @param error The error that occurred
   */
  private void onError(Throwable error) {
    setConnectionState(ConnectionState.FAILED);
    log.error("Error in SSE connection: {}", error.getMessage(), error);
    
    // Log additional context information that might help diagnose the issue
    log.error("Connection diagnostic info: URL={}, Last event received={} ms ago, Total events received={}, Queue size={}", 
              source.getUri(), 
              getTimeSinceLastEvent(),
              totalEventsReceived.get(), 
              queue.size());
              
    this.error = error;
  }

  /**
   * Checks if an error has occurred in the SSE client.
   *
   * @return true if an error has occurred, false otherwise
   */
  private boolean hasError() {
    return error != null;
  }

  /**
   * Closes all resources associated with the SSE client due to an error.
   * This ensures clean shutdown when recovering from error conditions.
   */
  private void closeResources() {
    log.info("Closing SSE client resources due to error condition: {}", 
             error != null ? error.getClass().getSimpleName() : "unknown");
    stop();
    close();
    log.info("SSE client resources successfully closed");
  }
}
