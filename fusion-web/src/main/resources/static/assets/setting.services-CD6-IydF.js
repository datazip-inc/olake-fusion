
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

import{r as e}from"./index-yHJHVwWK.js";function o(){return e.get("api/ams/v1/catalogs/metastore/types")}function g(t){return e.get(`api/ams/v1/catalogs/metastore/${t}/table-formats`)}function r(t){return e.get(`api/ams/v1/catalogs/metastore/${t}/storage-types`)}function i(t){return e.get(`api/ams/v1/catalogs/${t}`)}function c(t){return e.delete(`api/ams/v1/catalogs/${t}`)}function u(t){return e.get(`api/ams/v1/catalogs/${t}/delete/check`)}function l(t){const{isCreate:a,name:s}=t;return delete t.isCreate,a?e.post("api/ams/v1/catalogs",{...t}):e.put(`api/ams/v1/catalogs/${s}`,{...t})}function m(){return e.get("api/ams/v1/settings/system")}function p(){return e.get("api/ams/v1/settings/containers")}export{g as a,r as b,i as c,u as d,c as e,m as f,o as g,p as h,l as s};
