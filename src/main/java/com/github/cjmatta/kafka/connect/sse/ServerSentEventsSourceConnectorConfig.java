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
import org.apache.kafka.common.config.types.Password;

import java.util.Map;

public class ServerSentEventsSourceConnectorConfig extends AbstractConfig {
//  TODO: Add support for URL parameters
//  TODO: Add support for event type filtering
  public static final String SSE_URI = "sse.uri";
  private static final String SSE_URI_DOC = "URI for the SSE stream";
  public static final String TOPIC = "topic";
  private static final String TOPIC_DOC = "Topic to send events to";
  public static final String HTTP_BASIC_AUTH = "http.basic.auth";
//  HTTP Basic Auth Support
  private static final String HTTP_BASIC_AUTH_DOC = "Enable HTTP basic authentication";
  public static final String HTTP_BASIC_AUTH_USERNAME = "http.basic.auth.username";
  private static final String HTTP_BASIC_AUTH_USERNAME_DOC = "Username for HTTP basic authentication";
  public static final String HTTP_BASIC_AUTH_PASSWORD = "http.basic.auth.password";
  private static final String HTTP_BASIC_AUTH_PASSWORD_DOC = "Password for HTTP basic authentication";
  public static final String HTTP_HEADER_PREFIX = "http.header.";
  
  // Compression configuration
  public static final String COMPRESSION_ENABLED = "compression.enabled";
  private static final String COMPRESSION_ENABLED_DOC = "Enable gzip compression for HTTP requests";
  
  // Rate limiting configuration
  public static final String RATE_LIMIT_REQUESTS_PER_SECOND = "rate.limit.requests.per.second";
  private static final String RATE_LIMIT_REQUESTS_PER_SECOND_DOC = "Maximum number of requests per second (optional rate limiting)";
  public static final String RATE_LIMIT_MAX_CONCURRENT = "rate.limit.max.concurrent";
  private static final String RATE_LIMIT_MAX_CONCURRENT_DOC = "Maximum number of concurrent connections (optional rate limiting)";
  
  // Retry configuration
  public static final String RETRY_BACKOFF_INITIAL_MS = "retry.backoff.initial.ms";
  private static final String RETRY_BACKOFF_INITIAL_MS_DOC = "Initial backoff time in milliseconds for connection retries";
  public static final String RETRY_BACKOFF_MAX_MS = "retry.backoff.max.ms";
  private static final String RETRY_BACKOFF_MAX_MS_DOC = "Maximum backoff time in milliseconds for connection retries";
  public static final String RETRY_MAX_ATTEMPTS = "retry.max.attempts";
  private static final String RETRY_MAX_ATTEMPTS_DOC = "Maximum number of retry attempts (-1 for unlimited)";

  public final String sseUri;
  public final String topic;
  public final Boolean httpBasicAuth;
  public final String httpBasicAuthUsername;
  public final Password httpBasicAuthPassword;
  
  // New configuration fields
  public final Boolean compressionEnabled;
  public final Double rateLimitRequestsPerSecond;
  public final Integer rateLimitMaxConcurrent;
  public final Long retryBackoffInitialMs;
  public final Long retryBackoffMaxMs;
  public final Integer retryMaxAttempts;

  public ServerSentEventsSourceConnectorConfig(Map<?, ?> originals) {
    super(config(), originals);
    this.sseUri = this.getString(SSE_URI);
    this.topic = this.getString(TOPIC);
    this.httpBasicAuth = this.getBoolean(HTTP_BASIC_AUTH);
    this.httpBasicAuthUsername = this.getString(HTTP_BASIC_AUTH_USERNAME);
    this.httpBasicAuthPassword = this.getPassword(HTTP_BASIC_AUTH_PASSWORD);
    
    // Initialize new configuration fields
    this.compressionEnabled = this.getBoolean(COMPRESSION_ENABLED);
    this.rateLimitRequestsPerSecond = this.getDouble(RATE_LIMIT_REQUESTS_PER_SECOND);
    this.rateLimitMaxConcurrent = this.getInt(RATE_LIMIT_MAX_CONCURRENT);
    this.retryBackoffInitialMs = this.getLong(RETRY_BACKOFF_INITIAL_MS);
    this.retryBackoffMaxMs = this.getLong(RETRY_BACKOFF_MAX_MS);
    this.retryMaxAttempts = this.getInt(RETRY_MAX_ATTEMPTS);
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
          .documentation(TOPIC_DOC)
          .importance(Importance.HIGH)
          .build()
      )
      .define(
        ConfigKeyBuilder.of(HTTP_BASIC_AUTH, Type.BOOLEAN)
          .documentation(HTTP_BASIC_AUTH_DOC)
          .importance(Importance.MEDIUM)
          .defaultValue(false)
          .build()
      )
      .define(
        ConfigKeyBuilder.of(HTTP_BASIC_AUTH_USERNAME, Type.STRING)
          .documentation(HTTP_BASIC_AUTH_USERNAME_DOC)
          .importance(Importance.MEDIUM)
          .defaultValue(null)
          .build()
      )
      .define(
        ConfigKeyBuilder.of(HTTP_BASIC_AUTH_PASSWORD, Type.PASSWORD)
          .documentation(HTTP_BASIC_AUTH_PASSWORD_DOC)
          .importance(Importance.MEDIUM)
          .defaultValue(null)
          .build()
      )
      .define(
        ConfigKeyBuilder.of(COMPRESSION_ENABLED, Type.BOOLEAN)
          .documentation(COMPRESSION_ENABLED_DOC)
          .importance(Importance.LOW)
          .defaultValue(true)
          .build()
      )
      .define(
        ConfigKeyBuilder.of(RATE_LIMIT_REQUESTS_PER_SECOND, Type.DOUBLE)
          .documentation(RATE_LIMIT_REQUESTS_PER_SECOND_DOC)
          .importance(Importance.LOW)
          .defaultValue(null)
          .build()
      )
      .define(
        ConfigKeyBuilder.of(RATE_LIMIT_MAX_CONCURRENT, Type.INT)
          .documentation(RATE_LIMIT_MAX_CONCURRENT_DOC)
          .importance(Importance.LOW)
          .defaultValue(null)
          .build()
      )
      .define(
        ConfigKeyBuilder.of(RETRY_BACKOFF_INITIAL_MS, Type.LONG)
          .documentation(RETRY_BACKOFF_INITIAL_MS_DOC)
          .importance(Importance.LOW)
          .defaultValue(2000L)
          .build()
      )
      .define(
        ConfigKeyBuilder.of(RETRY_BACKOFF_MAX_MS, Type.LONG)
          .documentation(RETRY_BACKOFF_MAX_MS_DOC)
          .importance(Importance.LOW)
          .defaultValue(30000L)
          .build()
      )
      .define(
        ConfigKeyBuilder.of(RETRY_MAX_ATTEMPTS, Type.INT)
          .documentation(RETRY_MAX_ATTEMPTS_DOC)
          .importance(Importance.LOW)
          .defaultValue(-1)
          .build()
      );

  }

  public Map<String, Object> getHttpHeaders() {
    return originalsWithPrefix(HTTP_HEADER_PREFIX, true);
  }

}
