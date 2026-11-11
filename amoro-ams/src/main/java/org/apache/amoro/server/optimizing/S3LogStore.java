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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Object store for optimizing logs when the OLake stack runs in S3 storage mode. It reads the same
 * environment variables as OLake, OLake-UI and olake-worker ({@code OLAKE_STORAGE_MODE}, {@code
 * OLAKE_S3_*}) and builds its client the same way: static keys when both are set, else the AWS
 * default chain; path-style access when a custom endpoint (MinIO) is set.
 *
 * <p>Keys: {@code <OLAKE_S3_PREFIX>/fusion/logs/<processId>/<file>-<counter>.log}. The {@code
 * fusion/} folder keeps them apart from OLake's job folders under the same prefix.
 */
public class S3LogStore implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(S3LogStore.class);

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
  // Same as OLake-UI: MinIO may start after us, so wait up to 5 minutes for the bucket.
  private static final int ENSURE_BUCKET_ATTEMPTS = 60;
  private static final long ENSURE_BUCKET_RETRY_MS = 5_000;

  private final S3Client client;
  private final String bucket;
  private final String rootKey;
  private final boolean customEndpoint;

  S3LogStore(S3Client client, String bucket, String prefix, boolean customEndpoint) {
    this.client = client;
    this.bucket = bucket;
    String trimmedPrefix = trimSlashes(prefix);
    this.rootKey = trimmedPrefix.isEmpty() ? LOG_FOLDER : trimmedPrefix + "/" + LOG_FOLDER;
    this.customEndpoint = customEndpoint;
  }

  /**
   * Builds the store from the environment and checks that the bucket can be reached.
   *
   * @return the store, or null when {@code OLAKE_STORAGE_MODE} is not {@code s3}
   * @throws IllegalStateException if a required variable is missing or the bucket cannot be reached
   */
  public static S3LogStore fromEnv() {
    return fromEnv(System::getenv);
  }

  static S3LogStore fromEnv(Function<String, String> env) {
    if (!STORAGE_MODE_S3.equalsIgnoreCase(value(env, ENV_STORAGE_MODE))) {
      return null;
    }
    String bucket = required(env, ENV_S3_BUCKET);
    String region = required(env, ENV_S3_REGION);
    String endpoint = value(env, ENV_S3_ENDPOINT);

    S3ClientBuilder builder =
        S3Client.builder()
            // More than one HTTP client is on the AMS classpath; pick one explicitly.
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .region(Region.of(region))
            .credentialsProvider(credentials(env));
    if (!endpoint.isEmpty()) {
      // Path-style is required for MinIO and other S3-compatible endpoints.
      builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
    }

    S3LogStore store =
        new S3LogStore(builder.build(), bucket, value(env, ENV_S3_PREFIX), !endpoint.isEmpty());
    store.ensureBucket();
    LOG.info("Optimizing logs are stored in s3://{}/{}/", bucket, store.rootKey);
    return store;
  }

  private static AwsCredentialsProvider credentials(Function<String, String> env) {
    String accessKey = value(env, ENV_S3_ACCESS_KEY_ID);
    String secretKey = value(env, ENV_S3_SECRET_ACCESS_KEY);
    if (accessKey.isEmpty() || secretKey.isEmpty()) {
      return DefaultCredentialsProvider.create();
    }
    String sessionToken = value(env, ENV_S3_SESSION_TOKEN);
    return StaticCredentialsProvider.create(
        sessionToken.isEmpty()
            ? AwsBasicCredentials.create(accessKey, secretKey)
            : AwsSessionCredentials.create(accessKey, secretKey, sessionToken));
  }

  /**
   * Checks that the bucket exists. With a custom endpoint (MinIO) it creates a missing bucket and
   * retries while the endpoint starts, like OLake-UI.
   */
  private void ensureBucket() {
    if (!customEndpoint) {
      try {
        client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        return;
      } catch (RuntimeException e) {
        throw new IllegalStateException(
            "S3 bucket " + bucket + " for optimizing logs is not accessible", e);
      }
    }
    for (int attempt = 1; ; attempt++) {
      try {
        client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        return;
      } catch (RuntimeException headError) {
        try {
          client.createBucket(b -> b.bucket(bucket));
          return;
        } catch (BucketAlreadyExistsException | BucketAlreadyOwnedByYouException exists) {
          return;
        } catch (RuntimeException createError) {
          if (attempt >= ENSURE_BUCKET_ATTEMPTS) {
            throw new IllegalStateException(
                "Failed to ensure S3 bucket "
                    + bucket
                    + " for optimizing logs after "
                    + attempt
                    + " attempts",
                createError);
          }
          LOG.info("S3 bucket {} not reachable yet, retrying: {}", bucket, createError.toString());
        }
      }
      try {
        Thread.sleep(ENSURE_BUCKET_RETRY_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for S3 bucket " + bucket, e);
      }
    }
  }

  /** Key of one chunk: {@code <root>/<processId>/<fileBase>-<counter>.log}. */
  String chunkKey(long processId, String fileBase, long counter) {
    return processPrefix(processId) + LogChunks.chunkName(fileBase, counter);
  }

  /** Key prefix of a process, with a trailing slash. */
  String processPrefix(long processId) {
    return rootKey + "/" + processId + "/";
  }

  void put(String key, Path file) {
    client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), file);
  }

  /** Lists the object names (key without the prefix) directly under a prefix. */
  List<String> listNames(String prefix) {
    ListObjectsV2Request request =
        ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build();
    return client.listObjectsV2Paginator(request).contents().stream()
        .map(S3Object::key)
        .map(key -> key.substring(prefix.length()))
        .filter(name -> !name.isEmpty() && name.indexOf('/') < 0)
        .collect(Collectors.toList());
  }

  /** Reads an object, or returns null if it does not exist. */
  byte[] get(String key) {
    try {
      return client
          .getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
          .asByteArray();
    } catch (NoSuchKeyException e) {
      return null;
    }
  }

  String describe() {
    return "s3://" + bucket + "/" + rootKey;
  }

  @Override
  public void close() {
    client.close();
  }

  private static String required(Function<String, String> env, String key) {
    String value = value(env, key);
    if (value.isEmpty()) {
      throw new IllegalStateException(
          key + " is required when " + ENV_STORAGE_MODE + " is " + STORAGE_MODE_S3);
    }
    return value;
  }

  private static String value(Function<String, String> env, String key) {
    String value = env.apply(key);
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
