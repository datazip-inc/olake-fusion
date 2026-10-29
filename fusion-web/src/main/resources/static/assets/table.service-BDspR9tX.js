
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

import{r as o}from"./index-yHJHVwWK.js";function b(){return o.get("api/ams/v1/catalogs")}function c(a){const{catalog:t,keywords:s}=a;return o.get(`api/ams/v1/catalogs/${t}/databases`,{params:{keywords:s}})}function $(a){const{catalog:t,db:s,keywords:e}=a;return o.get(`api/ams/v1/catalogs/${t}/databases/${s}/tables`,{params:{keywords:e}})}function u({catalog:a="",db:t="",table:s="",token:e=""}){return o.get(`api/ams/v1/tables/catalogs/${a}/dbs/${t}/tables/${s}/details`,{params:{token:e}})}function d({catalog:a="",db:t="",table:s=""}){return o.get(`api/ams/v1/tables/catalogs/${a}/dbs/${t}/tables/${s}/hive/details`)}function m({catalog:a="",db:t="",table:s=""}){return o.get(`api/ams/v1/tables/catalogs/${a}/dbs/${t}/tables/${s}/upgrade/status`)}function f(a){const{catalog:t,db:s,table:e,filter:n,page:i,pageSize:g,token:l}=a;return o.get(`api/ams/v1/tables/catalogs/${t}/dbs/${s}/tables/${e}/partitions`,{params:{filter:n,page:i,pageSize:g,token:l}})}function v(a){const{catalog:t,db:s,table:e,partition:n,specId:i,page:g,pageSize:l,token:r}=a;return o.get(`api/ams/v1/tables/catalogs/${t}/dbs/${s}/tables/${e}/partitions/${n}/files`,{params:{specId:i,page:g,pageSize:l,token:r}})}function z(a){const{catalog:t,db:s,table:e,page:n,pageSize:i,token:g,ref:l,operation:r}=a;return o.get(`api/ams/v1/tables/catalogs/${t}/dbs/${s}/tables/${e}/snapshots`,{params:{page:n,pageSize:i,token:g,ref:l,operation:r}})}function k(a){const{catalog:t,db:s,table:e,snapshotId:n,page:i,pageSize:g,ref:l,token:r}=a;return o.get(`api/ams/v1/tables/catalogs/${t}/dbs/${s}/tables/${e}/snapshots/${n}/detail`,{params:{page:i,pageSize:g,ref:l,token:r}})}function S(a){const{catalog:t,db:s,table:e,page:n,pageSize:i,token:g}=a;return o.get(`api/ams/v1/tables/catalogs/${t}/dbs/${s}/tables/${e}/operations`,{params:{page:n,pageSize:i,token:g}})}function h(a){const{catalog:t,db:s,table:e,type:n,status:i,page:g,pageSize:l,token:r}=a;return o.get(`api/ams/v1/tables/catalogs/${t}/dbs/${s}/tables/${e}/optimizing-processes`,{params:{page:g,pageSize:l,token:r,type:n,status:i}})}function T(a){const{catalog:t,db:s,table:e,token:n}=a;return o.get(`api/ams/v1/tables/catalogs/${t}/dbs/${s}/tables/${e}/optimizing-types`,{params:{token:n}})}function y(a){const{catalog:t,db:s,table:e,processId:n,page:i,pageSize:g,token:l}=a;return o.get(`api/ams/v1/tables/catalogs/${t}/dbs/${s}/tables/${e}/optimizing-processes/${n}/tasks`,{params:{page:i,pageSize:g,token:l}})}function P({catalog:a="",db:t="",table:s="",properties:e={},pkList:n=[]}){return o.post(`api/ams/v1/tables/catalogs/${a}/dbs/${t}/tables/${s}/upgrade`,{properties:e,pkList:n})}function I(){return o.get("api/ams/v1/upgrade/properties")}function O({catalog:a="",db:t="",table:s="",processId:e=""}){return o.post(`api/ams/v1/tables/catalogs/${a}/dbs/${t}/tables/${s}/optimizing-processes/${e}/cancel`)}function D(a){const{catalog:t,db:s,table:e}=a;return o.get(`/api/ams/v1/tables/catalogs/${t}/dbs/${s}/tables/${e}/branches`)}function B(a){const{catalog:t,db:s,table:e}=a;return o.get(`/api/ams/v1/tables/catalogs/${t}/dbs/${s}/tables/${e}/tags`)}function L(a){const{catalog:t,db:s,table:e}=a;return o.get(`/api/ams/v1/tables/catalogs/${t}/dbs/${s}/tables/${e}/consumers`)}export{u as a,f as b,v as c,S as d,D as e,B as f,b as g,L as h,k as i,z as j,T as k,y as l,h as m,O as n,c as o,$ as p,m as q,d as r,I as s,P as u};
