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

package io.olake.fusion.io.reader;

import io.olake.fusion.utils.SerializationUtil;
import io.olake.fusion.utils.map.StructLikeWrapperSerializer;
import org.apache.amoro.shade.guava32.com.google.common.hash.Funnel;
import org.apache.amoro.shade.guava32.com.google.common.hash.PrimitiveSink;
import org.apache.iceberg.StructLike;
import org.jetbrains.annotations.NotNull;

public enum StructLikeFunnel implements Funnel<StructLike> {
  INSTANCE;

  StructLikeFunnel() {}

  @Override
  public void funnel(@NotNull StructLike structLike, PrimitiveSink primitiveSink) {
    StructLike copy = StructLikeWrapperSerializer.StructLikeCopy.copy(structLike);
    primitiveSink.putBytes(SerializationUtil.kryoSerialize(copy));
  }
}
