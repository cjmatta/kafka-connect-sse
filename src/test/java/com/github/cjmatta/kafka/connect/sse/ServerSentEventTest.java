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

package com.github.cjmatta.kafka.connect.sse;

import org.apache.kafka.connect.errors.DataException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerSentEventTest {
    private String data = "{\"data\": \"Hello World\"}";
    private String id = "12345";
    private String event = "message";

    @Test
    void testNullIdIsOK() {
        ServerSentEvent event = new ServerSentEvent(this.event, null, this.data);
        assertEquals("{\"data\": \"Hello World\"}", event.get(ServerSentEvent.DATA));
        assertEquals("message", event.get(ServerSentEvent.EVENT));
        assertNull(event.get(ServerSentEvent.ID));
    }

    @Test
    void testToString() {
        ServerSentEvent event = new ServerSentEvent(this.event, this.id, this.data);
        assertEquals("[event]=message [id]=12345 [data]={\"data\": \"Hello World\"}", event.toString());
    }
}