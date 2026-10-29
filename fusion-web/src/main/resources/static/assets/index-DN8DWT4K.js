
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

import{k as b,b as _,d as v,a5 as y,Y as k,a1 as S,a4 as l,B as s,Z as t,P as B,a8 as P}from"./vue-dGT8fLn8.js";import{u as F,l as I,_ as L}from"./index-yHJHVwWK.js";import{u as U}from"./usePlaceholder-f9rRK1EO.js";import{a as g,k as $,I as N,a2 as O,a3 as q,a4 as R,B as V,F as C}from"./antd-CJVa8hfK.js";import"./pinia-CEXqoBNK.js";import"./monaco-BvMftpTD.js";import"./sql-DI7PR0_6.js";import"./axios-BCn5QfZZ.js";const Q=b({name:"Login",setup(){const e=y(),o=_({username:"",password:""}),u=_(U()),c=async i=>{try{const r=F(),n=await I.login({user:i.username,password:i.password});if(n.code!==200){g.error(n.message);return}const{path:a,query:m}=r.historyPathInfo,p=a&&a!=="/login"?a:"/";e.replace({path:p,query:m})}catch(r){g.error(r.message)}},f=v(()=>!(o.username&&o.password));return{placeholder:u,formState:o,onFinish:c,disabled:f}}}),j=""+new URL("logo-all1-B0QkNmHQ.svg",import.meta.url).href,D={class:"login-wrap g-flex-jc"},E={class:"login-content"};function H(e,o,u,c,f,i){const r=O,n=N,a=$,m=R,p=q,h=V,w=C;return k(),S("div",D,[l("div",E,[o[2]||(o[2]=l("div",{class:"img-logo"},[l("img",{src:j,class:"arctic-logo",alt:""})],-1)),o[3]||(o[3]=l("div",{class:"content-title"}," Lakehouse management system ",-1)),s(w,{model:e.formState,name:"normal_login",class:"login-form label-120",onFinish:e.onFinish},{default:t(()=>[s(a,{label:"",name:"username",rules:[{required:!0,message:e.placeholder.usernamePh}]},{default:t(()=>[s(n,{value:e.formState.username,"onUpdate:value":o[0]||(o[0]=d=>e.formState.username=d),placeholder:e.placeholder.usernamePh,style:{height:"48px",background:"#fff"}},{prefix:t(()=>[s(r,{class:"site-form-item-icon"})]),_:1},8,["value","placeholder"])]),_:1},8,["rules"]),s(a,{label:"",name:"password",rules:[{required:!0,message:e.placeholder.passwordPh}]},{default:t(()=>[s(p,{value:e.formState.password,"onUpdate:value":o[1]||(o[1]=d=>e.formState.password=d),placeholder:e.placeholder.passwordPh,style:{height:"48px"}},{prefix:t(()=>[s(m,{class:"site-form-item-icon"})]),_:1},8,["value","placeholder"])]),_:1},8,["rules"]),s(a,null,{default:t(()=>[s(h,{disabled:e.disabled,type:"primary","html-type":"submit",class:"login-form-button"},{default:t(()=>[B(P(e.$t("signIn")),1)]),_:1},8,["disabled"])]),_:1})]),_:1},8,["model","onFinish"])])])}const M=L(Q,[["render",H],["__scopeId","data-v-4370f563"]]);export{M as default};
