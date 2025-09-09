/**
 * Copyright © 2019 Christopher Matta (chris.matta@gmail.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.cjmatta.kafka.connect.sse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class to check robots.txt compliance for SSE endpoints.
 * Implements basic robots.txt parsing and validation according to the robots exclusion standard.
 */
public class RobotsTxtChecker {
  private static final Logger log = LoggerFactory.getLogger(RobotsTxtChecker.class);

  private final String userAgent;
  private final Client httpClient;

  /**
   * Creates a new robots.txt checker with the specified user agent.
   *
   * @param userAgent The user agent string to use for robots.txt compliance checking
   */
  public RobotsTxtChecker(String userAgent) {
    this.userAgent = userAgent != null ? userAgent : "*";
    this.httpClient = ClientBuilder.newClient();
  }

  /**
   * Checks if access to the given URL is allowed according to robots.txt.
   *
   * @param url The URL to check
   * @return true if access is allowed, false if disallowed
   * @throws Exception if there's an error checking robots.txt
   */
  public boolean isAccessAllowed(String url) throws Exception {
    try {
      URI uri = new URI(url);
      String robotsTxtUrl = buildRobotsTxtUrl(uri);
      
      log.info("Checking robots.txt compliance for URL: {} using robots.txt at: {}", url, robotsTxtUrl);
      
      String robotsTxtContent = fetchRobotsTxt(robotsTxtUrl);
      if (robotsTxtContent == null) {
        log.info("No robots.txt found or accessible, assuming access is allowed");
        return true;
      }
      
      return parseRobotsTxt(robotsTxtContent, uri.getPath(), userAgent);
      
    } catch (URISyntaxException e) {
      log.error("Invalid URL format: {}", url, e);
      throw new Exception("Invalid URL format", e);
    }
  }

  /**
   * Builds the robots.txt URL from the given URI.
   */
  private String buildRobotsTxtUrl(URI uri) {
    return uri.getScheme() + "://" + uri.getHost() + 
           (uri.getPort() != -1 ? ":" + uri.getPort() : "") + "/robots.txt";
  }

  /**
   * Fetches the robots.txt content from the specified URL.
   *
   * @param robotsTxtUrl The robots.txt URL to fetch
   * @return The robots.txt content, or null if not accessible
   */
  private String fetchRobotsTxt(String robotsTxtUrl) {
    try {
      Response response = httpClient.target(robotsTxtUrl)
          .request()
          .header("User-Agent", userAgent)
          .get();

      if (response.getStatus() == 200) {
        String content = response.readEntity(String.class);
        log.debug("Successfully fetched robots.txt: {} characters", content.length());
        return content;
      } else if (response.getStatus() == 404) {
        log.info("No robots.txt found (404) at: {}", robotsTxtUrl);
        return null;
      } else {
        log.warn("Unexpected response {} when fetching robots.txt from: {}", 
                 response.getStatus(), robotsTxtUrl);
        return null;
      }
    } catch (Exception e) {
      log.warn("Failed to fetch robots.txt from: {} - {}", robotsTxtUrl, e.getMessage());
      return null;
    }
  }

  /**
   * Parses robots.txt content and determines if access to the given path is allowed.
   *
   * @param robotsTxtContent The robots.txt content to parse
   * @param path The path to check (e.g., "/v2/stream/recentchange")
   * @param userAgent The user agent to check rules for
   * @return true if access is allowed, false if disallowed
   */
  private boolean parseRobotsTxt(String robotsTxtContent, String path, String userAgent) {
    List<RobotRule> applicableRules = new ArrayList<>();
    String[] lines = robotsTxtContent.split("\n");
    
    String currentUserAgent = null;
    boolean userAgentMatches = false;
    
    for (String line : lines) {
      line = line.trim();
      
      // Skip comments and empty lines
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      
      String[] parts = line.split(":", 2);
      if (parts.length != 2) {
        continue;
      }
      
      String directive = parts[0].trim().toLowerCase();
      String value = parts[1].trim();
      
      if ("user-agent".equals(directive)) {
        // Start of a new user-agent block
        currentUserAgent = value;
        userAgentMatches = "*".equals(value) || 
                          userAgent.toLowerCase().contains(value.toLowerCase()) ||
                          value.toLowerCase().contains(userAgent.toLowerCase());
        
        log.debug("Found User-agent: {} (matches: {})", value, userAgentMatches);
        
      } else if (userAgentMatches && ("disallow".equals(directive) || "allow".equals(directive))) {
        // Add rule if it applies to our user agent
        boolean isDisallow = "disallow".equals(directive);
        applicableRules.add(new RobotRule(value, isDisallow));
        
        log.debug("Added rule: {} {} (for user-agent: {})", 
                  isDisallow ? "Disallow" : "Allow", value, currentUserAgent);
      }
    }
    
    // Check rules in order - most specific first
    applicableRules.sort((a, b) -> Integer.compare(b.pattern.length(), a.pattern.length()));
    
    for (RobotRule rule : applicableRules) {
      if (pathMatches(path, rule.pattern)) {
        boolean allowed = !rule.isDisallow;
        log.info("Path '{}' matched rule '{}' -> {}", 
                 path, (rule.isDisallow ? "Disallow: " : "Allow: ") + rule.pattern,
                 allowed ? "ALLOWED" : "DISALLOWED");
        return allowed;
      }
    }
    
    // No matching rule found - default is to allow
    log.info("No matching robots.txt rule found for path '{}' -> ALLOWED (default)", path);
    return true;
  }

  /**
   * Checks if a path matches a robots.txt pattern.
   * Implements basic pattern matching with wildcards.
   */
  private boolean pathMatches(String path, String pattern) {
    if (pattern.isEmpty()) {
      return true; // Empty pattern matches everything
    }
    
    if (pattern.equals("/")) {
      return true; // Root pattern matches everything
    }
    
    // Handle wildcards - convert to regex-like matching
    if (pattern.contains("*")) {
      String regexPattern = pattern.replace("*", ".*");
      return path.matches(regexPattern);
    }
    
    // Simple prefix matching
    return path.startsWith(pattern);
  }

  /**
   * Closes the HTTP client resources.
   */
  public void close() {
    if (httpClient != null) {
      httpClient.close();
    }
  }

  /**
   * Simple data class to represent a robots.txt rule.
   */
  private static class RobotRule {
    final String pattern;
    final boolean isDisallow;

    RobotRule(String pattern, boolean isDisallow) {
      this.pattern = pattern;
      this.isDisallow = isDisallow;
    }
  }
}