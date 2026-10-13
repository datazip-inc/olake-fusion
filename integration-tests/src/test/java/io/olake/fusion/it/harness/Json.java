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

package io.olake.fusion.it.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Strict access to Fusion's JSON. A missing field or a field of the wrong type is a contract
 * violation, never a silent default, so a renamed field fails loudly instead of reading as {@code
 * ""} or {@code 0}.
 */
public final class Json {
  public static final ObjectMapper MAPPER = new ObjectMapper();

  /** Fusion served JSON that does not match the contract. */
  public static class ContractViolation extends AssertionError {
    ContractViolation(String message) {
      super(message);
    }
  }

  private Json() {}

  public static JsonNode required(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null || value.isMissingNode()) {
      throw new ContractViolation("missing field '" + field + "' in " + node);
    }
    return value;
  }

  public static String text(JsonNode node, String field) {
    JsonNode value = required(node, field);
    if (!value.isTextual()) {
      throw new ContractViolation("field '" + field + "' is not text in " + node);
    }
    return value.asText();
  }

  /** Text or null; still fails when the field itself is absent. */
  public static String nullableText(JsonNode node, String field) {
    JsonNode value = required(node, field);
    return value.isNull() ? null : text(node, field);
  }

  public static long number(JsonNode node, String field) {
    JsonNode value = required(node, field);
    if (!value.isNumber()) {
      throw new ContractViolation("field '" + field + "' is not a number in " + node);
    }
    return value.asLong();
  }

  public static List<JsonNode> array(JsonNode node, String field) {
    return elements(required(node, field), field);
  }

  public static List<JsonNode> elements(JsonNode value, String what) {
    if (!value.isArray()) {
      throw new ContractViolation(what + " is not an array: " + value);
    }
    List<JsonNode> items = new ArrayList<>();
    value.forEach(items::add);
    return items;
  }
}
