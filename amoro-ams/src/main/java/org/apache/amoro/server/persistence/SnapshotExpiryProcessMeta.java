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

package org.apache.amoro.server.persistence;

import org.apache.amoro.process.ProcessStatus;

import java.util.Map;

/** One row of {@code snapshot_expiry_process}. */
public class SnapshotExpiryProcessMeta {
  private long processId;
  private long tableId;
  private ProcessStatus status;
  private int retryNumber;
  private long createTime;
  private long finishTime;
  private String failMessage;
  private Map<String, String> summary;

  public long getProcessId() {
    return processId;
  }

  public void setProcessId(long processId) {
    this.processId = processId;
  }

  public long getTableId() {
    return tableId;
  }

  public void setTableId(long tableId) {
    this.tableId = tableId;
  }

  public ProcessStatus getStatus() {
    return status;
  }

  public void setStatus(ProcessStatus status) {
    this.status = status;
  }

  public int getRetryNumber() {
    return retryNumber;
  }

  public void setRetryNumber(int retryNumber) {
    this.retryNumber = retryNumber;
  }

  public long getCreateTime() {
    return createTime;
  }

  public void setCreateTime(long createTime) {
    this.createTime = createTime;
  }

  public long getFinishTime() {
    return finishTime;
  }

  public void setFinishTime(long finishTime) {
    this.finishTime = finishTime;
  }

  public String getFailMessage() {
    return failMessage;
  }

  public void setFailMessage(String failMessage) {
    this.failMessage = failMessage;
  }

  public Map<String, String> getSummary() {
    return summary;
  }

  public void setSummary(Map<String, String> summary) {
    this.summary = summary;
  }
}
