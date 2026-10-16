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

/**
 * What Fusion promises to its users: API paths, JSON field names, statuses, optimizing types, table
 * property keys and the names of the group and catalog the stack creates.
 *
 * <p>Every name here is copied from Fusion's public surface on purpose. Do not replace any of them
 * with an import or a reference to product code. If Fusion renames one of them, the suite must
 * fail: that failure is the signal this package exists to give. Keep the package small; what is in
 * it is what Fusion promises, everything else is harness.
 */
package io.olake.fusion.it.contract;
