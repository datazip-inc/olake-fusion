
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

import{e as F,r as X,m as H,i as O,a as V,t as Y,g as Z,o as G,b as $,n as A,c as T,d as tt,w as et,f as st,h as nt}from"./vue-dGT8fLn8.js";/*!
 * pinia v2.1.7
 * (c) 2023 Eduardo San Martin Morote
 * @license MIT
 */let B;const E=t=>B=t,D=Symbol();function k(t){return t&&typeof t=="object"&&Object.prototype.toString.call(t)==="[object Object]"&&typeof t.toJSON!="function"}var C;(function(t){t.direct="direct",t.patchObject="patch object",t.patchFunction="patch function"})(C||(C={}));function lt(){const t=F(!0),o=t.run(()=>X({}));let s=[],e=[];const r=H({install(u){E(r),r._a=u,u.provide(D,r),u.config.globalProperties.$pinia=r,e.forEach(f=>s.push(f)),e=[]},use(u){return this._a?s.push(u):e.push(u),this},_p:s,_a:null,_e:t,_s:new Map,state:o});return r}const J=()=>{};function W(t,o,s,e=J){t.push(o);const r=()=>{const u=t.indexOf(o);u>-1&&(t.splice(u,1),e())};return!s&&Z()&&G(r),r}function P(t,...o){t.slice().forEach(s=>{s(...o)})}const ot=t=>t();function R(t,o){t instanceof Map&&o instanceof Map&&o.forEach((s,e)=>t.set(e,s)),t instanceof Set&&o instanceof Set&&o.forEach(t.add,t);for(const s in o){if(!o.hasOwnProperty(s))continue;const e=o[s],r=t[s];k(r)&&k(e)&&t.hasOwnProperty(s)&&!O(e)&&!V(e)?t[s]=R(r,e):t[s]=e}return t}const ct=Symbol();function rt(t){return!k(t)||!t.hasOwnProperty(ct)}const{assign:v}=Object;function ut(t){return!!(O(t)&&t.effect)}function at(t,o,s,e){const{state:r,actions:u,getters:f}=o,a=s.state.value[t];let g;function b(){a||(s.state.value[t]=r?r():{});const y=T(s.state.value[t]);return v(y,u,Object.keys(f||{}).reduce((d,m)=>(d[m]=H(tt(()=>{E(s);const _=s._s.get(t);return f[m].call(_,_)})),d),{}))}return g=N(t,b,o,s,e,!0),g}function N(t,o,s={},e,r,u){let f;const a=v({actions:{}},s),g={deep:!0};let b,y,d=[],m=[],_;const j=e.state.value[t];!u&&!j&&(e.state.value[t]={});let I;function L(c){let n;b=y=!1,typeof c=="function"?(c(e.state.value[t]),n={type:C.patchFunction,storeId:t,events:_}):(R(e.state.value[t],c),n={type:C.patchObject,payload:c,storeId:t,events:_});const h=I=Symbol();A().then(()=>{I===h&&(b=!0)}),y=!0,P(d,n,e.state.value[t])}const q=u?function(){const{state:n}=s,h=n?n():{};this.$patch(S=>{v(S,h)})}:J;function z(){f.stop(),d=[],m=[],e._s.delete(t)}function K(c,n){return function(){E(e);const h=Array.from(arguments),S=[],p=[];function Q(i){S.push(i)}function U(i){p.push(i)}P(m,{args:h,name:c,store:l,after:Q,onError:U});let x;try{x=n.apply(this&&this.$id===t?this:l,h)}catch(i){throw P(p,i),i}return x instanceof Promise?x.then(i=>(P(S,i),i)).catch(i=>(P(p,i),Promise.reject(i))):(P(S,x),x)}}const M={_p:e,$id:t,$onAction:W.bind(null,m),$patch:L,$reset:q,$subscribe(c,n={}){const h=W(d,c,n.detached,()=>S()),S=f.run(()=>et(()=>e.state.value[t],p=>{(n.flush==="sync"?y:b)&&c({storeId:t,type:C.direct,events:_},p)},v({},g,n)));return h},$dispose:z},l=$(M);e._s.set(t,l);const w=(e._a&&e._a.runWithContext||ot)(()=>e._e.run(()=>(f=F()).run(o)));for(const c in w){const n=w[c];if(O(n)&&!ut(n)||V(n))u||(j&&rt(n)&&(O(n)?n.value=j[c]:R(n,j[c])),e.state.value[t][c]=n);else if(typeof n=="function"){const h=K(c,n);w[c]=h,a.actions[c]=n}}return v(l,w),v(Y(l),w),Object.defineProperty(l,"$state",{get:()=>e.state.value[t],set:c=>{L(n=>{v(n,c)})}}),e._p.forEach(c=>{v(l,f.run(()=>c({store:l,app:e._a,pinia:e,options:a})))}),j&&u&&s.hydrate&&s.hydrate(l.$state,j),b=!0,y=!0,l}function ht(t,o,s){let e,r;const u=typeof o=="function";e=t,r=u?s:o;function f(a,g){const b=nt();return a=a||(b?st(D,null):null),a&&E(a),a=B,a._s.has(e)||(u?N(e,o,r,a):at(e,r,a)),a._s.get(e)}return f.$id=e,f}export{lt as c,ht as d};
