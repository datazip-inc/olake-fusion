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

package io.olake.fusion.utils;

import io.olake.fusion.optimizing.RewriteFilesOutput;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

/**
 * Task inputs and outputs are stored in Postgres (`optimizing_process_state`) as serialized bytes
 * that name their classes. The fixtures were written before the package rename, with
 * `org.apache.amoro` class names, and must keep loading so processes in flight survive an upgrade.
 */
public class TestLegacySerializedClassNames {

  private static byte[] fixture(String name) throws Exception {
    try (InputStream in =
        TestLegacySerializedClassNames.class.getResourceAsStream("/legacy-serialization/" + name)) {
      Assertions.assertNotNull(in, "missing fixture " + name);
      return in.readAllBytes();
    }
  }

  @Test
  public void javaSerializedOutputWithLegacyClassNamesLoads() throws Exception {
    Object restored = SerializationUtil.simpleDeserialize(fixture("rewrite-files-output.java.bin"));

    Assertions.assertTrue(restored instanceof RewriteFilesOutput, String.valueOf(restored));
    Assertions.assertEquals("3", ((RewriteFilesOutput) restored).summary().get("input-data-files"));
  }

  @Test
  public void kryoSerializedOutputWithLegacyClassNamesLoads() throws Exception {
    Object restored = SerializationUtil.kryoDeserialize(fixture("rewrite-files-output.kryo.bin"));

    Assertions.assertTrue(restored instanceof RewriteFilesOutput, String.valueOf(restored));
    Assertions.assertEquals("3", ((RewriteFilesOutput) restored).summary().get("input-data-files"));
  }
}
