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

import com.github.jcustenborder.kafka.connect.utils.config.ConfigKeyBuilder;
import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Type;

import java.util.Map;

public class ServerSentEventsSourceConnectorConfig extends AbstractConfig {
//  TODO: Add support for URL parameters
//  TODO: Add support for event type filtering
  public static final String SSE_URI = "sse.uri";
  private static final String SSE_URI_DOC = "URI for the SSE stream";
  public static final String TOPIC = "topic";
  private static final String TOPIC_CONF = "Topic to send events to";


  public final String sseUri;
  public final String topic;

  public ServerSentEventsSourceConnectorConfig(Map<?, ?> originals) {
    super(config(), originals);
    this.sseUri = this.getString(SSE_URI);
    this.topic = this.getString(TOPIC);
  }


  public static ConfigDef config() {

    return new ConfigDef()
      .define(
            ConfigKeyBuilder.of(SSE_URI, Type.STRING)
              .documentation(SSE_URI_DOC)
              .importance(Importance.HIGH)
              .build()
        )
      .define(
        ConfigKeyBuilder.of(TOPIC, Type.STRING)
          .documentation(TOPIC_CONF)
          .importance(Importance.HIGH)
          .build()
      );

  }


}
