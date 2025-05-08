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
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
