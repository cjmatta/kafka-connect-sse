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

import com.github.jcustenborder.kafka.connect.utils.VersionUtil;
import com.github.jcustenborder.kafka.connect.utils.config.Description;
import com.github.jcustenborder.kafka.connect.utils.config.TaskConfigs;
import com.github.jcustenborder.kafka.connect.utils.config.Title;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.source.SourceConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

@Description("Kafka Connect source connector for Server Sent Events")
@Title("Kafka Connect Server Sent Events") //This is the display name that will show up in the documentation.
public class ServerSentEventsSourceConnector extends SourceConnector {
  /*
    Your connector should never use System.out for logging. All of your classes should use slf4j
    for logging
 */
  private static Logger log = LoggerFactory.getLogger(ServerSentEventsSourceConnector.class);
  private ServerSentEventsSourceConnectorConfig config;
  Map<String, String> settings;

  @Override
  public String version() {
    return VersionUtil.version(this.getClass());
  }

  @Override
  public void start(Map<String, String> map) {
    log.info("Starting Server Sent Events Source Connector");
    config = new ServerSentEventsSourceConnectorConfig(map);
    this.settings = map;
  }

  @Override
  public Class<? extends Task> taskClass() {
    return ServerSentEventsSourceTask.class;
  }

  @Override
  public List<Map<String, String>> taskConfigs(int i) {
    return TaskConfigs.single(this.settings);
  }

  @Override
  public void stop() {
    log.info("Stopping Server Sent Events Source Connector");
  }

  @Override
  public ConfigDef config() {
    return ServerSentEventsSourceConnectorConfig.config();
  }
}
