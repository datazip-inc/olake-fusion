
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

import{u as P}from"./usePlaceholder-f9rRK1EO.js";import{Y as D,F as $,k as I,m as T,I as q}from"./antd-CJVa8hfK.js";import{k as w,b as d,Y as O,a1 as S,a4 as s,B as e,a8 as m,Z as n,r}from"./vue-dGT8fLn8.js";import{_ as U}from"./index-yHJHVwWK.js";import"./pinia-CEXqoBNK.js";import"./monaco-BvMftpTD.js";import"./sql-DI7PR0_6.js";import"./axios-BCn5QfZZ.js";const x={class:"create-table"},y={class:"nav-bar"},F={class:"title g-ml-8"},R={class:"content"},V={class:"basic"},Y={class:"title"},E=w({__name:"create",emits:["goBack"],setup(L,{emit:p}){const _=p,b=r(),f=r([{value:"catalog1",label:"catalog1"},{value:"catalog2",label:"catalog2"}]),g=r([{value:"database1",label:"database1"},{value:"database2",label:"database2"}]),a=d({catalog:"catalog1",database:"",tableName:""}),t=d(P());function h(){}function v(){}function k(){_("goBack")}return(i,o)=>{const B=D,u=T,c=I,C=q,N=$;return O(),S("div",x,[s("div",y,[e(B,{onClick:k}),s("span",F,m(i.$t("createTable")),1)]),s("div",R,[s("div",V,[s("p",Y,m(i.$t("basicInformation")),1),e(N,{ref_key:"formRef",ref:b,model:a,class:"label-120"},{default:n(()=>[e(c,{name:"catalog",label:"Catalog",rules:[{required:!0,message:`${t.selectClPh}`}]},{default:n(()=>[e(u,{value:a.catalog,"onUpdate:value":o[0]||(o[0]=l=>a.catalog=l),options:f.value,"show-search":"",placeholder:t.selectClPh,onChange:h},null,8,["value","options","placeholder"])]),_:1},8,["rules"]),e(c,{name:"database",label:"Database",rules:[{required:!0,message:`${t.selectDBPh}`}]},{default:n(()=>[e(u,{value:a.database,"onUpdate:value":o[1]||(o[1]=l=>a.database=l),options:g.value,"show-search":"",placeholder:t.selectDBPh,onChange:v},null,8,["value","options","placeholder"])]),_:1},8,["rules"]),e(c,{name:"tableName",label:"Table",rules:[{required:!0,message:`${t.inputTNPh}`}]},{default:n(()=>[e(C,{value:a.tableName,"onUpdate:value":o[2]||(o[2]=l=>a.tableName=l),placeholder:t.inputTNPh},null,8,["value","placeholder"])]),_:1},8,["rules"])]),_:1},8,["model"])])])])}}}),M=U(E,[["__scopeId","data-v-0be40d41"]]);export{M as default};
