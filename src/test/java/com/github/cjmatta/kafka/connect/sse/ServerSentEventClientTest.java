package com.github.cjmatta.kafka.connect.sse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.sse.InboundSseEvent;
import javax.ws.rs.sse.SseEventSource;
import java.io.IOException;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

public class ServerSentEventClientTest {
  private WebTarget webTarget;
  private SseEventSource sseEventSource;
  private SseEventSource.Builder sseEventSourceBuilder;
  private ServerSentEventClient sseClient;
  private MockedStatic<SseEventSource> mockedSseEventSource;
  private Invocation.Builder invocationBuilder;

  @BeforeEach
  public void setUp() {
    Client client = mock(Client.class);
    webTarget = mock(WebTarget.class);
    sseEventSource = mock(SseEventSource.class);
    sseEventSourceBuilder = mock(SseEventSource.Builder.class);
    invocationBuilder = mock(Invocation.Builder.class);
    mockedSseEventSource = Mockito.mockStatic(SseEventSource.class);

    // Mock URI for the WebTarget to prevent NullPointerException in metrics tests
    URI mockUri = URI.create("http://test.example.com/events");
    when(webTarget.getUri()).thenReturn(mockUri);
    
    when(client.target(anyString())).thenReturn(webTarget);
    when(webTarget.request()).thenReturn(invocationBuilder);
    when(invocationBuilder.header(anyString(), anyString())).thenReturn(invocationBuilder);
    when(invocationBuilder.accept(anyString())).thenReturn(invocationBuilder);
    when(SseEventSource.target(webTarget)).thenReturn(sseEventSourceBuilder); // Mocking static method
    when(sseEventSourceBuilder.reconnectingEvery(anyLong(), any())).thenReturn(sseEventSourceBuilder);
    when(sseEventSourceBuilder.build()).thenReturn(sseEventSource);

    sseClient = new ServerSentEventClient(client, webTarget, sseEventSource, "username", "password");
  }
  @AfterEach
  public void tearDown() {
    mockedSseEventSource.close(); // Release the static mock
  }

  @Test
  public void testStart() throws Exception {
    sseClient.start();
    verify(sseEventSource).register(any(Consumer.class), any(Consumer.class));
    verify(sseEventSource).open();
  }

  @Test
  public void testStop() {
    sseClient.stop();
    verify(sseEventSource).close();
  }

  @Test
  public void testBasicAuth() throws Exception {
    sseClient.start();

    String expectedHeader = "Basic " + Base64.getEncoder().encodeToString("username:password".getBytes());
    verify(invocationBuilder).header("Authorization", expectedHeader);
  }

  @Test
  public void testUserAgentHeader() throws Exception {
    // Test with custom configuration
    ServerSentEventClient customClient = new ServerSentEventClient(
        "http://test.com", null, null, "TestApp/1.0 (test@example.com)",
        true, null, null, 2000L, 30000L, -1, false);
    
    // We can't easily test the actual header without mocking the internal client creation
    // but we can verify the client was created with the correct user agent
    // This test mainly ensures the constructor accepts the parameters correctly
    assertEquals("INITIALIZED", customClient.getConnectionState().toString());
  }
  
  @Test
  public void testRateLimiting() throws Exception {
    // Test with rate limiting configuration
    ServerSentEventClient rateLimitedClient = new ServerSentEventClient(
        "http://test.com", null, null, "TestApp/1.0",
        true, 5.0, 2, 1000L, 10000L, 3, false);
    
    // Verify the client was created successfully with rate limiting parameters
    assertEquals("INITIALIZED", rateLimitedClient.getConnectionState().toString());
  }
  
  @Test
  public void testExponentialBackoffCalculation() throws Exception {
    // Create a client to access the calculateBackoffDelay method via reflection
    ServerSentEventClient testClient = new ServerSentEventClient(
        "http://test.com", null, null, "TestApp/1.0",
        true, null, null, 1000L, 30000L, -1, false);
    
    // Use reflection to test the calculateBackoffDelay method
    java.lang.reflect.Method calculateBackoffMethod = ServerSentEventClient.class
        .getDeclaredMethod("calculateBackoffDelay", int.class);
    calculateBackoffMethod.setAccessible(true);
    
    // Test exponential backoff progression
    long delay1 = (Long) calculateBackoffMethod.invoke(testClient, 1);
    long delay2 = (Long) calculateBackoffMethod.invoke(testClient, 2);
    long delay3 = (Long) calculateBackoffMethod.invoke(testClient, 3);
    
    assertEquals(1000L, delay1); // Initial delay
    assertEquals(2000L, delay2); // 1000 * 2^1
    assertEquals(4000L, delay3); // 1000 * 2^2
    
    // Test that delays are capped at maximum
    long delayMax = (Long) calculateBackoffMethod.invoke(testClient, 20);
    assertEquals(30000L, delayMax); // Should be capped at retryBackoffMaxMs
  }
  
  @Test
  public void testRateLimitErrorDetection() throws Exception {
    ServerSentEventClient testClient = new ServerSentEventClient(
        "http://test.com", null, null, "TestApp/1.0",
        true, null, null, 1000L, 30000L, -1, false);
    
    // Use reflection to test the isRateLimitError method
    java.lang.reflect.Method isRateLimitErrorMethod = ServerSentEventClient.class
        .getDeclaredMethod("isRateLimitError", Throwable.class);
    isRateLimitErrorMethod.setAccessible(true);
    
    // Test various error messages
    Exception rateLimitException = new Exception("HTTP 429 Too Many Requests");
    Exception tooManyRequestsException = new Exception("Server responded with: too many requests");
    Exception rateLimitTextException = new Exception("Rate limit exceeded");
    Exception normalException = new Exception("Connection timeout");
    
    assertTrue((Boolean) isRateLimitErrorMethod.invoke(testClient, rateLimitException));
    assertTrue((Boolean) isRateLimitErrorMethod.invoke(testClient, tooManyRequestsException));
    assertTrue((Boolean) isRateLimitErrorMethod.invoke(testClient, rateLimitTextException));
    assertFalse((Boolean) isRateLimitErrorMethod.invoke(testClient, normalException));
    assertFalse((Boolean) isRateLimitErrorMethod.invoke(testClient, (Throwable) null));
  }

  @Test
  public void testGetRecords() throws InterruptedException {
    InboundSseEvent event1 = mock(InboundSseEvent.class);
    InboundSseEvent event2 = mock(InboundSseEvent.class);
    when(event1.getName()).thenReturn("event1");
    when(event2.getName()).thenReturn("event2");

    sseClient.getQueueForTesting().add(event1);
    sseClient.getQueueForTesting().add(event2);

    List<InboundSseEvent> records = sseClient.getRecords();

    assertEquals(2, records.size());
    assertTrue(records.contains(event1));
    assertTrue(records.contains(event2));
  }

  @Test
  public void testOnMessage() throws IOException {
    InboundSseEvent event = mock(InboundSseEvent.class);
    when(event.getId()).thenReturn("1");
    when(event.getName()).thenReturn("testEvent");
    when(event.readData()).thenReturn("testData");

    ArgumentCaptor<Consumer<InboundSseEvent>> messageCaptor = ArgumentCaptor.forClass(Consumer.class);
    ArgumentCaptor<Consumer<Throwable>> errorCaptor = ArgumentCaptor.forClass(Consumer.class);

    sseClient.start();

    verify(sseEventSource).register(messageCaptor.capture(), errorCaptor.capture());
    messageCaptor.getValue().accept(event);

    assertEquals(1, sseClient.getQueueForTesting().size());
    assertTrue(sseClient.getQueueForTesting().contains(event));
  }

  /**
   * Tests that metrics are properly collected when events are processed.
   * This verifies our metrics collection implementation correctly tracks:
   * - Event counts
   * - Data bytes
   * - Event type statistics 
   */
  @Test
  public void testMetricsCollection() throws IOException {
    // Setup test events with different types and data sizes
    InboundSseEvent event1 = mock(InboundSseEvent.class);
    when(event1.getId()).thenReturn("1");
    when(event1.getName()).thenReturn("typeA");
    when(event1.readData()).thenReturn("small data");

    InboundSseEvent event2 = mock(InboundSseEvent.class);
    when(event2.getId()).thenReturn("2");
    when(event2.getName()).thenReturn("typeB");
    when(event2.readData()).thenReturn("this is a longer data payload for testing byte counting");

    InboundSseEvent event3 = mock(InboundSseEvent.class);
    when(event3.getId()).thenReturn("3");
    when(event3.getName()).thenReturn("typeA");
    when(event3.readData()).thenReturn("another typeA event");

    // Get message handler
    ArgumentCaptor<Consumer<InboundSseEvent>> messageCaptor = ArgumentCaptor.forClass(Consumer.class);
    sseClient.start();
    verify(sseEventSource).register(messageCaptor.capture(), any(Consumer.class));
    
    // Process events through the onMessage handler
    Consumer<InboundSseEvent> onMessageHandler = messageCaptor.getValue();
    onMessageHandler.accept(event1);
    onMessageHandler.accept(event2);
    onMessageHandler.accept(event3);

    // Get metrics and verify counts
    Map<String, Object> metrics = sseClient.getMetrics();
    
    // Check basic event metrics
    assertEquals(3L, metrics.get("events.total"));
    
    // The total bytes should be the sum of all event data lengths
    int expectedBytes = "small data".length() + 
                        "this is a longer data payload for testing byte counting".length() +
                        "another typeA event".length();
    assertEquals((long)expectedBytes, metrics.get("events.bytes"));
    
    // Verify queue size metrics
    assertEquals(3, metrics.get("queue.size"));
    assertEquals(3L, metrics.get("queue.maxSize"));
    
    // Verify event type counts are tracked correctly
    @SuppressWarnings("unchecked")
    Map<String, Long> eventTypes = (Map<String, Long>) metrics.get("events.byType");
    assertEquals(2L, eventTypes.get("typeA"));
    assertEquals(1L, eventTypes.get("typeB"));
    
    // Verify connection metrics were updated
    assertEquals("CONNECTED", metrics.get("connection.state"));
    assertEquals(1L, metrics.get("connection.attempts"));
    assertEquals(1L, metrics.get("connection.successful"));
    assertEquals(0L, metrics.get("connection.failed"));
    assertEquals(0L, metrics.get("connection.errors"));
  }

  /**
   * Tests the connection health check functionality.
   * This verifies:
   * - A newly connected client is considered healthy
   * - A client with no events for longer than the idle timeout is considered unhealthy
   * - A client in a failed state is considered unhealthy
   */
  @Test
  public void testConnectionHealthCheck() throws IOException, NoSuchFieldException, IllegalAccessException {
    // Start with a healthy connection
    sseClient.start();
    
    // A freshly started connection should be healthy
    assertTrue(sseClient.isConnectionHealthy());
    
    // Set a shorter idle timeout for testing
    sseClient.setIdleTimeout(1000); // 1 second
    
    // Use reflection to simulate a stalled connection by setting lastEventTimestamp to a time in the past
    java.lang.reflect.Field lastEventTimestampField = ServerSentEventClient.class.getDeclaredField("lastEventTimestamp");
    lastEventTimestampField.setAccessible(true);
    
    // Set the last event time to 2 seconds ago (exceeding our 1-second timeout)
    long stalledTimestamp = System.currentTimeMillis() - 2000;
    lastEventTimestampField.set(sseClient, stalledTimestamp);
    
    // Now the connection should be considered unhealthy due to idle timeout
    assertFalse(sseClient.isConnectionHealthy());
    
    // Reset the timestamp to be recent
    lastEventTimestampField.set(sseClient, System.currentTimeMillis());
    
    // Connection should be healthy again
    assertTrue(sseClient.isConnectionHealthy());
    
    // Test connection state affect on health
    // Use reflection to change the connection state to FAILED
    java.lang.reflect.Field connectionStateField = ServerSentEventClient.class.getDeclaredField("connectionState");
    connectionStateField.setAccessible(true);
    connectionStateField.set(sseClient, ServerSentEventClient.ConnectionState.FAILED);
    
    // A failed connection should be considered unhealthy
    assertFalse(sseClient.isConnectionHealthy());
  }
  
  @Test
  public void testRobotsTxtConfiguration() throws Exception {
    // Test that robots.txt checking can be enabled
    ServerSentEventClient robotsEnabledClient = new ServerSentEventClient(
        "http://test.com", null, null, "TestApp/1.0",
        true, null, null, 1000L, 30000L, -1, true);
    
    // Verify the client was created successfully with robots.txt checking enabled
    assertEquals("INITIALIZED", robotsEnabledClient.getConnectionState().toString());
    
    // Note: We can't easily test the actual robots.txt checking without
    // setting up a mock HTTP server, but this test ensures the configuration
    // parameter is properly accepted and the client initializes correctly.
  }
}
