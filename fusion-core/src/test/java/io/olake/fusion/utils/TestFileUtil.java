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

import org.junit.Assert;
import org.junit.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;

public class TestFileUtil {

  @Test
  public void getFileName() {
    String fileName =
        TableFileUtil.getFileName(
            "hdfs://easyops-sloth/user/warehouse/animal_partition_two/base/"
                + "opt_mon=202109/opt_day=26/00000-0-3-1-37128f07-0845-43d8-905b-bd69b4ca351c-0000000001.parquet");
    Assert.assertEquals(
        "00000-0-3-1-37128f07-0845-43d8-905b-bd69b4ca351c-0000000001.parquet", fileName);
  }

  @Test
  public void getFileDir() {
    String fileDir =
        TableFileUtil.getFileDir(
            "hdfs://easyops-sloth/user/warehouse/animal_partition_two/base/"
                + "opt_mon=202109/opt_day=26/00000-0-3-1-37128f07-0845-43d8-905b-bd69b4ca351c-0000000001.parquet");
    Assert.assertEquals(
        "hdfs://easyops-sloth/user/warehouse/animal_partition_two/base/opt_mon=202109/opt_day=26",
        fileDir);
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "/a/b/c, hdfs://xxxxx/a/b/c",
        "/a/b/c, hdfs://localhost:8888/a/b/c",
        "/a/b/c, file://xxxxx/a/b/c",
        "/a/b/c, /a/b/c",
        "/a/b/c, hdfs:/a/b/c",
        "a/b/c, a/b/c"
      })
  public void testGetUriPath(String expected, String path) {
    Assert.assertEquals(expected, TableFileUtil.getUriPath(path));
  }

  @Test
  public void testGetParent() {
    String filePath =
        "hdfs://easyops-sloth/user/warehouse/animal_partition_two/base/"
            + "opt_mon=202109/opt_day=26/00000-0-3-1-37128f07-0845-43d8-905b-bd69b4ca351c-0000000001.parquet";
    String parentDir = TableFileUtil.getParent(filePath);
    Assert.assertEquals(
        "hdfs://easyops-sloth/user/warehouse/animal_partition_two/base/"
            + "opt_mon=202109/opt_day=26",
        parentDir);

    // test s3 scheme
    filePath =
        "s3://my-bucket/user/warehouse/animal_partition_two/base/"
            + "opt_mon=202109/opt_day=26/00000-0-3-1-37128f07-0845-43d8-905b-bd69b4ca351c-0000000001.parquet";
    parentDir = TableFileUtil.getParent(filePath);
    Assert.assertEquals(
        "s3://my-bucket/user/warehouse/animal_partition_two/base/" + "opt_mon=202109/opt_day=26",
        parentDir);

    // test wrapped by URI
    URI uri = URI.create(filePath);
    parentDir = TableFileUtil.getParent(uri.toString());
    Assert.assertEquals(
        "s3://my-bucket/user/warehouse/animal_partition_two/base/" + "opt_mon=202109/opt_day=26",
        parentDir);
    // lose scheme when getting path from URI
    parentDir = TableFileUtil.getParent(uri.getPath());
    Assert.assertEquals(
        "/user/warehouse/animal_partition_two/base/opt_mon=202109/opt_day=26", parentDir);

    // test no scheme
    filePath =
        "/user/warehouse/animal_partition_two/base/opt_mon=202109/opt_day=26/"
            + "00000-0-3-1-37128f07-0845-43d8-905b-bd69b4ca351c-0000000001.parquet";
    parentDir = TableFileUtil.getParent(filePath);
    Assert.assertEquals(
        "/user/warehouse/animal_partition_two/base/opt_mon=202109/opt_day=26", parentDir);

    // test root path
    filePath = "/00000-0-3-1-37128f07-0845-43d8-905b-bd69b4ca351c-0000000001.parquet";
    parentDir = TableFileUtil.getParent(filePath);
    Assert.assertEquals("/", parentDir);
  }
}
