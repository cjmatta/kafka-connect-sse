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

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.sse.InboundSseEvent;
import jakarta.ws.rs.sse.SseEventSource;

import java.io.Closeable;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
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
  
  // Additional headers
  private final Map<String, Object> headers;
  
  // Store configuration for enhanced HTTP behavior
  private final boolean compressionEnabled;
  private final Double rateLimitRequestsPerSecond;
  private final Integer rateLimitMaxConcurrent;
  private final long retryBackoffInitialMs;
  private final long retryBackoffMaxMs;
  private final int retryMaxAttempts;
  
  // Rate limiting and retry fields
  private volatile long lastRequestTime = 0;
  private volatile int currentRetryAttempt = 0;

  // Track current connection state
  private volatile ConnectionState connectionState;
  // Store the last time an event was received
  private volatile long lastEventTimestamp;
  
  // Metrics tracking fields
  private final AtomicLong totalEventsReceived = new AtomicLong(0);
  private final AtomicLong totalBytesReceived = new AtomicLong(0);
  private final AtomicLong totalConnectionAttempts = new AtomicLong(0);
  private final AtomicLong totalSuccessfulConnections = new AtomicLong(0);
  private final AtomicLong totalFailedConnections = new AtomicLong(0);
  private final AtomicLong totalConnectionErrors = new AtomicLong(0);
  private final AtomicLong totalReconnections = new AtomicLong(0);
  
  // Performance metrics
  private volatile long connectedSince = 0;
  private volatile long lastReconnectTime = 0;
  private final AtomicLong maxQueueSize = new AtomicLong(0);
  
  // Event type counters - useful for monitoring specific event patterns
  private final Map<String, AtomicLong> eventTypeCounters = new ConcurrentHashMap<>();
  
  private volatile Throwable error;

  /**
   * Creates a new SSE client for the given URL without authentication.
   *
   * @param url The URL of the SSE stream
   */
  public ServerSentEventClient(String url) {
    this(url, null, null);
  }

  /**
   * Creates a new SSE client for the given URL with basic authentication.
   *
   * @param url      The URL of the SSE stream
   * @param username Username for basic authentication
   * @param password Password for basic authentication
   */
  ServerSentEventClient(String url, String username, String password) {
    this(url, username, password, null, true, null, null, 2000L, 30000L, -1);
  }

  ServerSentEventClient(String url, String username, String password, Map<String, Object> headers) {
    this(url, username, password, headers, true, null, null, 2000L, 30000L, -1);
  }

  /**
   * Creates a new SSE client with full configuration.
   *
   * @param url                        The URL of the SSE stream
   * @param username                   Username for basic authentication (null if not needed)
   * @param password                   Password for basic authentication (null if not needed)
   * @param headers                    Custom HTTP headers
   * @param compressionEnabled         Whether to enable gzip compression
   * @param rateLimitRequestsPerSecond Rate limit for requests per second (null if not needed)
   * @param rateLimitMaxConcurrent     Maximum concurrent connections (null if not needed)
   * @param retryBackoffInitialMs      Initial backoff time for retries
   * @param retryBackoffMaxMs          Maximum backoff time for retries
   * @param retryMaxAttempts           Maximum retry attempts (-1 for unlimited)
   */
  public ServerSentEventClient(String url, String username, String password, Map<String, Object> headers,
                              boolean compressionEnabled, Double rateLimitRequestsPerSecond,
                              Integer rateLimitMaxConcurrent, long retryBackoffInitialMs,
                              long retryBackoffMaxMs, int retryMaxAttempts) {
    log.info("Initializing SSE Client for URL: {} with enhanced configuration", url);
    this.client = createClient(compressionEnabled);
    this.source = client.target(url);
    this.username = username;
    this.password = password;
    this.headers = headers;
    this.compressionEnabled = compressionEnabled;
    this.rateLimitRequestsPerSecond = rateLimitRequestsPerSecond;
    this.rateLimitMaxConcurrent = rateLimitMaxConcurrent;
    this.retryBackoffInitialMs = retryBackoffInitialMs;
    this.retryBackoffMaxMs = retryBackoffMaxMs;
    this.retryMaxAttempts = retryMaxAttempts;
    this.queue = new LinkedBlockingDeque<>();
    this.connectionState = ConnectionState.INITIALIZED;
    this.lastEventTimestamp = System.currentTimeMillis();
    this.currentRetryAttempt = 0;
    log.info("SSE Client initialized with compression: {}, rate limit: {}/sec", 
             compressionEnabled, rateLimitRequestsPerSecond);
  }

  /**
   * Constructor for testing purposes.
   */
  ServerSentEventClient(Client client, WebTarget source, SseEventSource sse, String url, String username, String password, Map<String, Object> headers) {
    log.info("Initializing SSE Client for testing");
    this.client = client;
    this.source = source;
    this.username = username;
    this.password = password;
    this.headers = headers;
    this.compressionEnabled = true;
    this.rateLimitRequestsPerSecond = null;
    this.rateLimitMaxConcurrent = null;
    this.retryBackoffInitialMs = 2000L;
    this.retryBackoffMaxMs = 30000L;
    this.retryMaxAttempts = -1;
    this.queue = new LinkedBlockingDeque<>();
    this.sse = sse;
    this.connectionState = ConnectionState.INITIALIZED;
    this.lastEventTimestamp = System.currentTimeMillis();
    this.currentRetryAttempt = 0;
    log.info("SSE Client initialized in state: {}", connectionState);
  }

  /**
   * Creates and configures the HTTP client based on configuration.
   * 
   * @param compressionEnabled Whether to enable gzip compression
   * @return Configured Jersey Client
   */
  private Client createClient(boolean compressionEnabled) {
    ClientBuilder builder = ClientBuilder.newBuilder();
    
    if (compressionEnabled) {
      // Jersey will automatically handle gzip compression when this property is set
      builder.property("jersey.config.client.useEncoding", "gzip");
    }
    
    return builder.build();
  }

  /**
   * Applies rate limiting by sleeping if necessary to respect the configured requests per second.
   */
  private void applyRateLimit() {
    if (rateLimitRequestsPerSecond == null || rateLimitRequestsPerSecond <= 0) {
      return;
    }
    
    long currentTime = System.currentTimeMillis();
    long timeSinceLastRequest = currentTime - lastRequestTime;
    long minIntervalMs = (long) (1000.0 / rateLimitRequestsPerSecond);
    
    if (timeSinceLastRequest < minIntervalMs) {
      long sleepTime = minIntervalMs - timeSinceLastRequest;
      log.debug("Rate limiting: sleeping for {} ms to respect {} requests/second", sleepTime, rateLimitRequestsPerSecond);
      try {
        Thread.sleep(sleepTime);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("Rate limiting sleep interrupted", e);
      }
    }
    
    lastRequestTime = System.currentTimeMillis();
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
      
      // Apply compression headers if enabled
      if (compressionEnabled) {
        builder.header("Accept-Encoding", "gzip, deflate");
        log.debug("Added compression headers");
      }
      
      // Apply default User-Agent if not provided in custom headers
      boolean hasUserAgent = headers != null && headers.containsKey("User-Agent");
      if (!hasUserAgent) {
        String defaultUserAgent = "KafkaConnectSSE/1.4 (https://github.com/cjmatta/kafka-connect-sse)";
        builder.header("User-Agent", defaultUserAgent);
        log.debug("Added default User-Agent header: {}", defaultUserAgent);
      }
      
      // Custom request headers (can override default User-Agent if specified)
      if (headers != null) {
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
          builder.header(entry.getKey(), entry.getValue());
          log.debug("Added custom header: {}={}", entry.getKey(), entry.getValue());
        }
      }
      
      // Apply rate limiting if configured
      if (rateLimitRequestsPerSecond != null && rateLimitRequestsPerSecond > 0) {
        applyRateLimit();
      }

      long reconnectInterval = Math.min(retryBackoffInitialMs, 2000L);
      log.debug("Configuring SSE event source with reconnection interval of {} milliseconds", reconnectInterval);
      sse = SseEventSource
        .target(this.source)
        .reconnectingEvery(reconnectInterval, TimeUnit.MILLISECONDS)
        .build();

      sse.register(this::onMessage, this::onError);
      log.info("Opening SSE client connection...");
      sse.open();
      setConnectionState(ConnectionState.CONNECTED);
      log.info("SSE client successfully connected to {}", source.getUri());
      
      // Update connection metrics
      totalConnectionAttempts.incrementAndGet();
      totalSuccessfulConnections.incrementAndGet();
      connectedSince = System.currentTimeMillis();
      log.info("Connection metrics updated: TotalAttempts={}, TotalSuccess={}", 
               totalConnectionAttempts.get(), totalSuccessfulConnections.get());
    } catch (Exception e) {
      setConnectionState(ConnectionState.FAILED);
      log.error("Failed to start SSE client connection: {}", e.getMessage(), e);
      totalConnectionAttempts.incrementAndGet();
      totalFailedConnections.incrementAndGet();
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
   * Gets a summary of the client's current status for monitoring purposes.
   * This provides a snapshot of the client state including connection status,
   * event counts, and timing information.
   *
   * @return A string containing the status summary
   */
  public String getStatusSummary() {
    StringBuilder statusBuilder = new StringBuilder();
    statusBuilder.append("SSE Client Status: ")
                 .append("State=").append(connectionState)
                 .append(", URL=").append(source.getUri())
                 .append(", Events=").append(totalEventsReceived.get())
                 .append(", QueueSize=").append(queue.size())
                 .append(", LastEventAge=").append(getTimeSinceLastEvent()).append("ms")
                 .append(", HasError=").append(hasError());
                 
    if (hasError() && error != null) {
      // Safe access to error properties to prevent NPEs
      String errorType = error.getClass().getSimpleName();
      String errorMessage = error.getMessage() != null ? error.getMessage() : "No message";
      statusBuilder.append(", ErrorType=").append(errorType)
                   .append(", ErrorMsg=").append(errorMessage);
    }
    
    return statusBuilder.toString();
  }

  /**
   * Logs the current status of the SSE client at the specified log level.
   * This is useful for periodically logging the client state for monitoring.
   * 
   * @param useWarnLevel If true, logs at WARN level instead of INFO level
   */
  public void logStatus(boolean useWarnLevel) {
    String status = getStatusSummary();
    if (useWarnLevel) {
      log.warn(status);
    } else {
      log.info(status);
    }
  }

  /**
   * Returns the time (in milliseconds) since the last event was received.
   *
   * @return Time in milliseconds since last event
   */
  public long getTimeSinceLastEvent() {
    return System.currentTimeMillis() - lastEventTimestamp;
  }

  // Default values for health check configuration
  private static final long DEFAULT_IDLE_TIMEOUT_MS = 60000;  // 1 minute
  private static final long DEFAULT_CONNECTION_CHECK_INTERVAL_MS = 30000;  // 30 seconds
  
  // Health check configuration fields
  private long idleTimeoutMs = DEFAULT_IDLE_TIMEOUT_MS;
  private long connectionCheckIntervalMs = DEFAULT_CONNECTION_CHECK_INTERVAL_MS;
  private volatile long lastConnectionCheckTimestamp = System.currentTimeMillis();
  
  /**
   * Checks if the connection appears to be healthy.
   * A connection is considered unhealthy if:
   * 1. The connection state is not CONNECTED
   * 2. There's an error
   * 3. No events have been received within the idle timeout period
   *
   * @return true if the connection is healthy, false otherwise
   */
  public boolean isConnectionHealthy() {
    // Check if current state is not CONNECTED
    if (connectionState != ConnectionState.CONNECTED) {
      log.warn("Connection is not in CONNECTED state. Current state: {}", connectionState);
      return false;
    }
    
    // Check if there's an error
    if (hasError()) {
      log.warn("Connection has an error: {}", error.getMessage());
      return false;
    }
    
    // Check if we've exceeded the idle timeout
    long timeSinceLastEvent = getTimeSinceLastEvent();
    if (timeSinceLastEvent > idleTimeoutMs) {
      log.warn("Connection appears to be stalled. No events received in {} ms", timeSinceLastEvent);
      return false;
    }
    
    return true;
  }
  
  /**
   * Sets the idle timeout in milliseconds.
   * If no events are received within this timeout, the connection is considered stalled.
   *
   * @param idleTimeoutMs the idle timeout in milliseconds
   */
  public void setIdleTimeout(long idleTimeoutMs) {
    if (idleTimeoutMs <= 0) {
      throw new IllegalArgumentException("Idle timeout must be positive");
    }
    this.idleTimeoutMs = idleTimeoutMs;
    log.info("Set SSE client idle timeout to {} ms", idleTimeoutMs);
  }
  
  /**
   * Sets how often the connection should be checked for health.
   *
   * @param connectionCheckIntervalMs the connection check interval in milliseconds
   */
  public void setConnectionCheckInterval(long connectionCheckIntervalMs) {
    if (connectionCheckIntervalMs <= 0) {
      throw new IllegalArgumentException("Connection check interval must be positive");
    }
    this.connectionCheckIntervalMs = connectionCheckIntervalMs;
    log.info("Set SSE client connection check interval to {} ms", connectionCheckIntervalMs);
  }

  // Method for testing
  BlockingQueue<InboundSseEvent> getQueueForTesting() {
    return queue;
  }


  public List<InboundSseEvent> getRecords() throws InterruptedException {
    // Perform connection health check at regular intervals
    long now = System.currentTimeMillis();
    if (now - lastConnectionCheckTimestamp > connectionCheckIntervalMs) {
      performConnectionHealthCheck();
      lastConnectionCheckTimestamp = now;
    }
    
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
      // Get first and last event IDs safely to prevent NPEs
      String firstEventId = records.get(0).getId() != null ? records.get(0).getId() : "<null>";
      String lastEventId = records.get(records.size() - 1).getId() != null ? 
                          records.get(records.size() - 1).getId() : "<null>";
      
      log.debug("Returning {} records. First event ID: {}, Last event ID: {}", 
              records.size(), firstEventId, lastEventId);
    }
    
    return records;
  }
  
  /**
   * Performs a health check on the connection and takes appropriate action if unhealthy.
   * This method is called periodically during the getRecords() calls.
   */
  private void performConnectionHealthCheck() {
    log.debug("Performing connection health check");
    
    // Skip health check if connection is already in a non-connected state
    if (connectionState != ConnectionState.CONNECTED) {
      log.debug("Skipping health check because connection state is: {}", connectionState);
      return;
    }
    
    // Check if the connection has exceeded the idle timeout
    long timeSinceLastEvent = getTimeSinceLastEvent();
    if (timeSinceLastEvent > idleTimeoutMs) {
      log.warn("Connection stalled - no events received in {} ms (timeout: {} ms)", 
               timeSinceLastEvent, idleTimeoutMs);
      
      // Log detailed diagnostics
      log.info("Connection diagnostics at timeout: URL={}, Total events={}, Queue size={}", 
               source.getUri(), totalEventsReceived.get(), queue.size());
      
      // The connection might be in a zombie state, attempt to reconnect
      attemptReconnection();
    } else {
      // Only log healthy connection status at the INFO level occasionally
      if (timeSinceLastEvent > idleTimeoutMs / 2) {
        log.info("Connection health check passed but no events for a while: {} ms", timeSinceLastEvent);
      } else {
        log.debug("Connection health check passed: Last event {} ms ago", timeSinceLastEvent);
      }
    }
  }
  
  /**
   * Attempts to reconnect the SSE client when a connection issue is detected.
   * This method closes the existing connection and opens a new one.
   */
  private void attemptReconnection() {
    // Check if we've exceeded max retry attempts
    if (retryMaxAttempts != -1 && currentRetryAttempt >= retryMaxAttempts) {
      log.error("Maximum retry attempts ({}) exceeded, giving up reconnection", retryMaxAttempts);
      setConnectionState(ConnectionState.FAILED);
      return;
    }
    
    currentRetryAttempt++;
    
    // Calculate exponential backoff delay
    long backoffMs = calculateBackoffDelay(currentRetryAttempt);
    log.info("Attempting reconnection #{} with {}ms backoff due to connection issue", currentRetryAttempt, backoffMs);
    
    try {
      // Apply exponential backoff delay
      if (backoffMs > 0) {
        Thread.sleep(backoffMs);
      }
      
      // Close existing connection
      stop();
      
      // Clear any existing error
      error = null;
      
      // Attempt to establish a new connection
      start();
      log.info("Successfully reconnected SSE client on attempt #{}", currentRetryAttempt);
      totalReconnections.incrementAndGet();
      lastReconnectTime = System.currentTimeMillis();
      // Reset retry counter on successful connection
      currentRetryAttempt = 0;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Reconnection attempt interrupted", e);
      setConnectionState(ConnectionState.FAILED);
      error = e;
    } catch (IOException e) {
      log.error("Failed to reconnect SSE client on attempt #{}: {}", currentRetryAttempt, e.getMessage(), e);
      setConnectionState(ConnectionState.FAILED);
      error = e;
      totalConnectionErrors.incrementAndGet();
      
      // Check if this was a rate limiting error (HTTP 429)
      if (isRateLimitError(e)) {
        log.warn("Rate limit error detected, extending backoff time");
        // Rate limit errors get longer backoffs
        currentRetryAttempt = Math.max(currentRetryAttempt, 3);
      }
    }
  }
  
  /**
   * Calculates the exponential backoff delay for the given retry attempt.
   * 
   * @param attempt The retry attempt number (1-based)
   * @return The backoff delay in milliseconds
   */
  private long calculateBackoffDelay(int attempt) {
    if (attempt <= 1) {
      return retryBackoffInitialMs;
    }
    
    // Exponential backoff: initial * 2^(attempt-1), capped at max
    long delay = retryBackoffInitialMs * (long) Math.pow(2, attempt - 1);
    return Math.min(delay, retryBackoffMaxMs);
  }
  
  /**
   * Checks if an exception indicates a rate limiting error.
   * 
   * @param exception The exception to check
   * @return true if the exception indicates rate limiting
   */
  private boolean isRateLimitError(Throwable exception) {
    if (exception == null) {
      return false;
    }
    
    String message = exception.getMessage();
    if (message != null) {
      String lowerMessage = message.toLowerCase();
      return lowerMessage.contains("429") || 
             lowerMessage.contains("too many requests") ||
             lowerMessage.contains("rate limit");
    }
    
    return false;
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
    totalBytesReceived.addAndGet(event.readData() != null ? event.readData().length() : 0);
    
    // Update event type counter - but check for null event name first
    // ConcurrentHashMap doesn't allow null keys, so we need to handle this case
    String eventName = event.getName();
    if (eventName != null) {
      eventTypeCounters.computeIfAbsent(eventName, k -> new AtomicLong(0)).incrementAndGet();
    } else {
      log.debug("Received event with null name, not updating event type counters. Event ID: {}", event.getId());
    }
    
    if (log.isDebugEnabled()) {
      log.debug("Received SSE event - ID: {}, Name: {}, Data length: {}", 
                event.getId(), 
                eventName, 
                event.readData() != null ? event.readData().length() : 0);
    }
    
    if (totalEventsReceived.get() % 100 == 0) {
      log.info("Processed {} events total, current queue size: {}", 
               totalEventsReceived.get(), queue.size());
    }
    
    this.queue.add(event);
    updateMaxQueueSize();
  }

  /**
   * Handles errors from the SSE connection.
   * Updates connection state and stores the error.
   *
   * @param error The error that occurred
   */
  private void onError(Throwable error) {
    setConnectionState(ConnectionState.FAILED);
    // Safe extraction of error message to prevent NPE
    String errorMessage = error != null ? error.getMessage() : "Unknown error (null)";
    log.error("Error in SSE connection: {}", errorMessage, error);
    
    // Log additional context information that might help diagnose the issue
    log.error("Connection diagnostic info: URL={}, Last event received={} ms ago, Total events received={}, Queue size={}", 
              source.getUri(), 
              getTimeSinceLastEvent(),
              totalEventsReceived.get(), 
              queue.size());
              
    this.error = error;
    totalConnectionErrors.incrementAndGet();
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
  
  /**
   * Returns a map of all metrics collected by this SSE client.
   * This is useful for monitoring the health and performance of the connector.
   *
   * @return Map of metric names to values
   */
  public Map<String, Object> getMetrics() {
    Map<String, Object> metrics = new ConcurrentHashMap<>();
    
    // Connection metrics
    metrics.put("connection.state", connectionState.toString());
    metrics.put("connection.url", source.getUri().toString());
    metrics.put("connection.attempts", totalConnectionAttempts.get());
    metrics.put("connection.successful", totalSuccessfulConnections.get());
    metrics.put("connection.failed", totalFailedConnections.get());
    metrics.put("connection.errors", totalConnectionErrors.get());
    metrics.put("connection.reconnections", totalReconnections.get());
    metrics.put("connection.hasError", hasError());
    
    if (hasError() && error != null) {
      // Safe access to error properties to prevent NPEs
      metrics.put("connection.errorType", error.getClass().getName());
      // Handle potential null error message
      metrics.put("connection.errorMessage", error.getMessage() != null ? error.getMessage() : "No message");
    }
    
    // Time-based metrics
    metrics.put("time.sinceLastEvent", getTimeSinceLastEvent());
    metrics.put("time.uptime", connectionState == ConnectionState.CONNECTED ? 
                (System.currentTimeMillis() - connectedSince) : 0);
    metrics.put("time.sinceLastReconnect", lastReconnectTime > 0 ? 
                (System.currentTimeMillis() - lastReconnectTime) : -1);
    
    // Event metrics
    metrics.put("events.total", totalEventsReceived.get());
    metrics.put("events.bytes", totalBytesReceived.get());
    metrics.put("queue.size", queue.size());
    metrics.put("queue.maxSize", maxQueueSize.get());
    
    // Event type metrics
    Map<String, Long> eventTypes = new ConcurrentHashMap<>();
    eventTypeCounters.forEach((type, count) -> eventTypes.put(type, count.get()));
    metrics.put("events.byType", eventTypes);
    
    return metrics;
  }
  
  /**
   * Returns a specific metric value by name.
   * 
   * @param name The name of the metric to retrieve
   * @return The value of the metric, or null if the metric doesn't exist
   */
  public Object getMetric(String name) {
    return getMetrics().get(name);
  }
  
  /**
   * Logs all metrics at the specified log level.
   * This is useful for periodic reporting of connector status.
   * 
   * @param useWarnLevel If true, logs at WARN level, otherwise at INFO level
   */
  public void logMetrics(boolean useWarnLevel) {
    Map<String, Object> metrics = getMetrics();
    
    if (useWarnLevel) {
      log.warn("SSE Client Metrics: {}", metrics);
    } else {
      log.info("SSE Client Metrics: {}", metrics);
    }
  }
  
  /**
   * Updates the maximum queue size metric if the current queue size is larger.
   * Called internally when events are added to the queue.
   */
  private void updateMaxQueueSize() {
    int currentSize = queue.size();
    long currentMax = maxQueueSize.get();
    
    if (currentSize > currentMax) {
      maxQueueSize.set(currentSize);
    }
  }
}
