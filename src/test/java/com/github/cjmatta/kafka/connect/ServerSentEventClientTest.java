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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.ws.rs.sse.InboundSseEvent;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServerSentEventClientTest {
  private ServerSentEventClient client;

  @BeforeEach
  void setUp() throws IOException {
    String WIKIPEDIA_SSE_STREAM = "https://stream.wikimedia.org/v2/stream/recentchange";
    this.client = new ServerSentEventClient(WIKIPEDIA_SSE_STREAM);
    this.client.start();
  }

  @AfterEach
  void tearDown() {
    this.client.stop();
  }

  @Test
  void getRecords() throws InterruptedException {
    List<InboundSseEvent> records = new LinkedList<>();
    Thread.sleep(2000);
    records = this.client.getRecords();

    assertFalse(records.isEmpty());

  }
}