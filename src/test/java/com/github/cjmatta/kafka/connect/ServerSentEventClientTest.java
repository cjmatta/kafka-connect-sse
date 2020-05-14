/*
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
 */

package com.github.cjmatta.kafka.connect;

import com.github.cjmatta.kafka.connect.sse.ServerSentEventClient;
import com.launchdarkly.eventsource.MessageEvent;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

public class ServerSentEventClientTest {
  private static final int CHUNK_SIZE = 5;

  @Test
  public void testSchemalessClient() throws Exception {
    String body = "data: {\"key\": 1, \"value\": \"a\"}\n\n";

    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(createEventsResponse(body, SocketPolicy.KEEP_OPEN));
      server.start();

      ServerSentEventClient client = new ServerSentEventClient(server.url("/").toString());
      client.start();
      List<MessageEvent> records = client.getRecords();
      assertTrue(records.size() > 0);
      assertEquals("{\"key\": 1, \"value\": \"a\"}", records.get(0).getData());
    }
  }

  private MockResponse createEventsResponse(String body, SocketPolicy socketPolicy) {
    return new MockResponse()
      .setHeader("Content-Type", "text/event-stream")
      .setChunkedBody(body, CHUNK_SIZE)
      .setSocketPolicy(socketPolicy);
  }

}