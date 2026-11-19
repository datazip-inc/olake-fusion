/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified by Datazip Inc. in 2026
 */

package org.apache.amoro.server.optimizing;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;

/**
 * Object store for optimizing logs when {@code OLAKE_STORAGE_MODE} is {@code s3}. It reads the same
 * {@code OLAKE_STORAGE_MODE} and {@code OLAKE_S3_*} variables as OLake, OLake-UI and olake-worker
 * and builds its client the same way: static keys when both are set, else the AWS default chain;
 * path-style access when a custom endpoint (MinIO) is set.
 *
 * <p>One object per process: {@code <OLAKE_S3_PREFIX>/fusion/logs/<type>/<processId>.log}, where
 * {@code <type>} is {@code optimization} or {@code cleanup} (see {@link LogType}). The {@code
 * fusion/} folder keeps them apart from OLake's job folders under the same prefix.
 */
public class S3LogStore {

  static final String ENV_STORAGE_MODE = "OLAKE_STORAGE_MODE";
  static final String STORAGE_MODE_S3 = "s3";
  static final String ENV_S3_BUCKET = "OLAKE_S3_BUCKET";
  static final String ENV_S3_REGION = "OLAKE_S3_REGION";
  static final String ENV_S3_PREFIX = "OLAKE_S3_PREFIX";
  static final String ENV_S3_ENDPOINT = "OLAKE_S3_ENDPOINT";
  static final String ENV_S3_ACCESS_KEY_ID = "OLAKE_S3_ACCESS_KEY_ID";
  static final String ENV_S3_SECRET_ACCESS_KEY = "OLAKE_S3_SECRET_ACCESS_KEY";
  static final String ENV_S3_SESSION_TOKEN = "OLAKE_S3_SESSION_TOKEN";

  private static final String LOG_FOLDER = "fusion/logs";

  /** Kind of process a log belongs to; it names the log's folder. */
  public enum LogType {
    // Compaction, including its planning failures.
    OPTIMIZATION("optimization"),
    // Snapshot expiry.
    CLEANUP("cleanup");

    private final String folder;

    LogType(String folder) {
      this.folder = folder;
    }
  }

  private final S3Client client;
  private final String bucket;
  private final String rootKey;

  private S3LogStore(S3Client client, String bucket, String prefix) {
    this.client = client;
    this.bucket = bucket;
    String trimmedPrefix = trimSlashes(prefix);
    this.rootKey = trimmedPrefix.isEmpty() ? LOG_FOLDER : trimmedPrefix + "/" + LOG_FOLDER;
  }

  /**
   * Builds the store from the environment.
   *
   * @return the store, or null when {@code OLAKE_STORAGE_MODE} is not {@code s3}
   * @throws IllegalStateException if a required variable is missing
   */
  public static S3LogStore fromEnv() {
    if (!STORAGE_MODE_S3.equalsIgnoreCase(value(ENV_STORAGE_MODE))) {
      return null;
    }
    String bucket = required(ENV_S3_BUCKET);
    String region = required(ENV_S3_REGION);
    String endpoint = value(ENV_S3_ENDPOINT);

    S3ClientBuilder builder =
        S3Client.builder()
            // More than one HTTP client is on the AMS classpath; pick one explicitly.
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .region(Region.of(region))
            .credentialsProvider(credentials());
    if (!endpoint.isEmpty()) {
      // Path-style is required for MinIO and other S3-compatible endpoints.
      builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
    }
    return new S3LogStore(builder.build(), bucket, value(ENV_S3_PREFIX));
  }

  private static AwsCredentialsProvider credentials() {
    String accessKey = value(ENV_S3_ACCESS_KEY_ID);
    String secretKey = value(ENV_S3_SECRET_ACCESS_KEY);
    if (accessKey.isEmpty() || secretKey.isEmpty()) {
      return DefaultCredentialsProvider.create();
    }
    String sessionToken = value(ENV_S3_SESSION_TOKEN);
    return StaticCredentialsProvider.create(
        sessionToken.isEmpty()
            ? AwsBasicCredentials.create(accessKey, secretKey)
            : AwsSessionCredentials.create(accessKey, secretKey, sessionToken));
  }

  void put(LogType type, long processId, byte[] content) {
    client.putObject(
        PutObjectRequest.builder().bucket(bucket).key(key(type, processId)).build(),
        RequestBody.fromBytes(content));
  }

  /**
   * Reads the log of a process, or returns null if it was not uploaded. Without {@code
   * s3:ListBucket}, S3 answers a missing key with 403 instead of 404, so 403 also counts as not
   * uploaded; a real permission problem shows up when the log is uploaded.
   */
  byte[] get(LogType type, long processId) {
    try {
      return client
          .getObjectAsBytes(
              GetObjectRequest.builder().bucket(bucket).key(key(type, processId)).build())
          .asByteArray();
    } catch (NoSuchKeyException e) {
      return null;
    } catch (S3Exception e) {
      if (e.statusCode() == 403) {
        return null;
      }
      throw e;
    }
  }

  private String key(LogType type, long processId) {
    return rootKey + "/" + type.folder + "/" + processId + ".log";
  }

  private static String required(String key) {
    String value = value(key);
    if (value.isEmpty()) {
      throw new IllegalStateException(
          key + " is required when " + ENV_STORAGE_MODE + " is " + STORAGE_MODE_S3);
    }
    return value;
  }

  private static String value(String key) {
    String value = System.getenv(key);
    return value == null ? "" : value.trim();
  }

  private static String trimSlashes(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && value.charAt(start) == '/') {
      start++;
    }
    while (end > start && value.charAt(end - 1) == '/') {
      end--;
    }
    return value.substring(start, end);
  }
}
