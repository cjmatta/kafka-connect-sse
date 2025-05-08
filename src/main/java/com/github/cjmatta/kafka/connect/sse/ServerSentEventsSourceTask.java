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
 **/

package com.github.cjmatta.kafka.connect.sse;

import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import com.github.jcustenborder.kafka.connect.utils.VersionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.sse.InboundSseEvent;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class ServerSentEventsSourceTask extends SourceTask {

  static final Logger log = LoggerFactory.getLogger(ServerSentEventsSourceTask.class);
  ServerSentEventsSourceConnectorConfig config;
  ServerSentEventClient client;


  @Override
  public String version() {
    return VersionUtil.version(this.getClass());
  }

  @Override
  public void start(Map<String, String> map) {
    log.info("Starting Server Sent Events Source Task");
    config = new ServerSentEventsSourceConnectorConfig(map);
    if(config.httpBasicAuth) {
      client = new ServerSentEventClient(config.getString(ServerSentEventsSourceConnectorConfig.SSE_URI),
          config.getString(ServerSentEventsSourceConnectorConfig.HTTP_BASIC_AUTH_USERNAME),
          config.getString(ServerSentEventsSourceConnectorConfig.HTTP_BASIC_AUTH_PASSWORD));
    } else {
      client = new ServerSentEventClient(config.getString(ServerSentEventsSourceConnectorConfig.SSE_URI));
    }

    try {
      client.start();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  @Override
  public List<SourceRecord> poll() throws InterruptedException {
    List<InboundSseEvent> sseEvents = client.getRecords();
    List<SourceRecord> records = new LinkedList<>();

    for (InboundSseEvent event : sseEvents) {
      records.add(createSourceRecordFromSseEvent(event));
    }

    return records;
  }

  private SourceRecord createSourceRecordFromSseEvent(InboundSseEvent event) {
    Map<String, ?> srcOffset = Collections.emptyMap();
    Map<String, ?> srcPartition = Collections.emptyMap();

    log.debug("Event " + event.toString());


    ServerSentEvent serverSentEvent = new ServerSentEvent(
        event.getName(),
        event.getId(),
        event.readData()
    );

    return new SourceRecord(
      srcPartition,
      srcOffset,
      this.config.getString(ServerSentEventsSourceConnectorConfig.TOPIC),
      null,
      null,
      ServerSentEvent.SCHEMA,
      serverSentEvent
    );

  }

  @Override
  public void stop() {
    this.client.stop();
  }
}