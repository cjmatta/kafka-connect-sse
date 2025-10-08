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
    when(invocationBuilder.header(eq("Authorization"), anyString())).thenReturn(invocationBuilder);
    when(invocationBuilder.header(eq("Custom-Header"), anyString())).thenReturn(invocationBuilder);

    when(invocationBuilder.header(anyString(), anyString())).thenReturn(invocationBuilder);
    when(invocationBuilder.accept(anyString())).thenReturn(invocationBuilder);
    when(SseEventSource.target(webTarget)).thenReturn(sseEventSourceBuilder); // Mocking static method
    when(sseEventSourceBuilder.reconnectingEvery(anyLong(), any())).thenReturn(sseEventSourceBuilder);
    when(sseEventSourceBuilder.build()).thenReturn(sseEventSource);

    final Map<String, Object> headers = Map.of("Custom-Header", "HeaderValue");
    sseClient = new ServerSentEventClient(client, webTarget, sseEventSource, null, "username", "password", headers);
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
  public void testCustomRequestHeader() throws Exception {
    sseClient.start();

    final String expectedHeader = "HeaderValue";
    verify(invocationBuilder).header("Custom-Header", expectedHeader);
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
}
