
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

import{k as z,V as N,b as S,w as O,Y as o,a1 as v,a4 as a,B as y,Z as l,W as c,a3 as w,F as C,r as K,X as T,a7 as q,a8 as u,a5 as F,a6 as U}from"./vue-dGT8fLn8.js";import{f as Z,h as D}from"./setting.services-CD6-IydF.js";import{u as G}from"./usePageScroll-DPtxTTIV.js";import{y as L,q as M,E as W,Z as X,$ as Y}from"./antd-CJVa8hfK.js";import{_ as H}from"./index-yHJHVwWK.js";import"./pinia-CEXqoBNK.js";import"./monaco-BvMftpTD.js";import"./sql-DI7PR0_6.js";import"./axios-BCn5QfZZ.js";const J={class:"setting-wrap"},Q={class:"system-setting"},ee={class:"container-setting"},te={class:"content"},ae={class:"item"},se={class:"left"},ne={class:"right"},oe={key:0,class:"item"},re={class:"left"},ie={class:"right"},le={class:"g-mb-12 g-mt-12"},ce={class:"g-mb-12 g-mt-12"},ue=z({__name:"index",setup(pe){const{t:d}=N(),B=F(),A=U(),{pageScrollRef:$}=G(),p=K(!1),f=S([]),g=S([]),k=S([{title:d("key"),dataIndex:"key",width:340,ellipsis:!0},{title:d("value"),dataIndex:"value"}]),h=K([]),i={system:{title:d("systemSetting"),key:"system"},container:{title:d("containerSetting"),key:"container"}},m=K(i.system.key);O(()=>A,t=>{var e;m.value=((e=t.query)==null?void 0:e.tab)||i.system.key,E(m.value)},{immediate:!0});async function x(){try{p.value=!0;const t=await Z();if(!t)return;f.length=0,Object.keys(t).forEach(e=>{f.push({key:e,value:t[e]})})}finally{p.value=!1}}async function P(){try{p.value=!0;const t=await D();h.value=[],g.length=0,(t||[]).forEach((e,_)=>{e.propertiesArray=[],h.value.push(e.name),g.push(e),Object.keys(e.properties||{}).forEach(n=>{var r;(r=g[_].propertiesArray)==null||r.push({key:n,value:e.properties[n]})}),(e.optimizeGroup||[]).forEach(n=>{n.innerPropertiesArray=[],Object.keys(n.properties||{}).forEach(r=>{n.innerPropertiesArray.push({key:r,value:n.properties[r]})})})})}finally{p.value=!1}}function E(t){const e={...A.query};e.tab=t,B.replace({query:{...e}}),R()}function R(){m.value===i.system.key?x():P()}return(t,e)=>{const _=M,n=W,r=Y,I=X,V=L,j=T("u-loading");return o(),v(C,null,[a("div",{class:"page-scroll",ref_key:"pageScrollRef",ref:$},[a("div",J,[y(V,{activeKey:m.value,"onUpdate:activeKey":e[1]||(e[1]=s=>m.value=s),onChange:E},{default:l(()=>[(o(),c(n,{key:i.system.key,tab:i.system.title},{default:l(()=>[a("div",Q,[f.length?(o(),c(_,{key:0,"row-key":"key",columns:k,"data-source":f,pagination:!1},null,8,["columns","data-source"])):w("",!0)])]),_:1},8,["tab"])),(o(),c(n,{key:i.container.key,tab:i.container.title},{default:l(()=>[a("div",ee,[y(I,{activeKey:h.value,"onUpdate:activeKey":e[0]||(e[0]=s=>h.value=s)},{default:l(()=>[(o(!0),v(C,null,q(g,s=>(o(),c(r,{key:s.name,header:s.name},{default:l(()=>[a("ul",te,[a("li",ae,[a("h3",se,u(t.$t("name")),1),a("span",ne,u(s.name),1)]),s.classpath?(o(),v("li",oe,[a("h3",re,u(t.$t("implementation")),1),a("span",ie,u(s.classpath),1)])):w("",!0)]),a("h3",le,u(t.$t("properties")),1),y(_,{"row-key":"key",columns:k,"data-source":s.propertiesArray,pagination:!1},null,8,["columns","data-source"]),a("h3",ce,u(t.$t("optimizerGroups")),1),y(I,null,{default:l(()=>[(o(!0),v(C,null,q(s.optimizeGroup,b=>(o(),c(r,{key:b.name,header:b.name},{default:l(()=>[y(_,{"row-key":"name",columns:k,"data-source":b.innerPropertiesArray,pagination:!1},null,8,["columns","data-source"])]),_:2},1032,["header"]))),128))]),_:2},1024)]),_:2},1032,["header"]))),128))]),_:1},8,["activeKey"])])]),_:1},8,["tab"]))]),_:1},8,["activeKey"])])],512),p.value?(o(),c(j,{key:0})):w("",!0)],64)}}}),be=H(ue,[["__scopeId","data-v-47df7adf"]]);export{be as default};
