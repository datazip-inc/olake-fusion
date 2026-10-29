
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

import{i as d,A as p,C as g,a as m,b as u,S as h,M as f,P as C,c as v,d as w,e as y,f as S,g as _,h as $,j as z,k as I,l as L,m as M,n as O,o as b,p as x,q as P,r as B,s as T,t as U,u as V,v as D,w as j,x as k,y as A,z as E,B as o,D as R,E as G,F as J,G as N,H as Y,I as W,J as Z,K as q,L as F,N as H,O as K,Q,R as X,T as ee,U as se,V as ae,W as te,X as ne,Y as re,Z as l,_ as ie,$ as oe,a0 as le,a1 as ce,a2 as de,a3 as pe,a4 as ge,a5 as me,a6 as ue,a7 as he,a8 as fe,a9 as Ce,aa as ve,ab as we,ac as ye,ad as Se,ae as _e}from"./echarts-BorDrwyo.js";import{k as $e,c as ze,w as Ie,z as Le,l as Me,r as Oe,Y as be,W as xe,Z as Pe,a4 as Be,ad as Te}from"./vue-dGT8fLn8.js";import{_ as Ue}from"./index-yHJHVwWK.js";import{S as Ve}from"./antd-CJVa8hfK.js";const De=Object.freeze(Object.defineProperty({__proto__:null,Axis:p,ChartView:g,ComponentModel:m,ComponentView:u,List:h,Model:f,PRIORITY:C,SeriesModel:v,color:w,connect:y,dataTool:S,dependencies:_,disConnect:$,disconnect:z,dispose:I,env:L,extendChartView:M,extendComponentModel:O,extendComponentView:b,extendSeriesModel:x,format:P,getCoordinateSystemDimensions:B,getInstanceByDom:T,getInstanceById:U,getMap:V,graphic:D,helper:j,init:d,innerDrawElementOnCanvas:k,matrix:A,number:E,parseGeoJSON:o,parseGeoJson:o,registerAction:R,registerCoordinateSystem:G,registerCustomSeries:J,registerLayout:N,registerLoading:Y,registerLocale:W,registerMap:Z,registerPostInit:q,registerPostUpdate:F,registerPreprocessor:H,registerProcessor:K,registerTheme:Q,registerTransform:X,registerUpdateLifecycle:ee,registerVisual:se,setCanvasCreator:ae,setPlatformAPI:te,throttle:ne,time:re,use:l,util:ie,vector:oe,version:le,zrUtil:ce,zrender:de},Symbol.toStringTag,{value:"Module"}));l([pe,ge,me,ue,he,fe,Ce,ve,we,ye,Se,_e]);const je=$e({props:{width:{type:String,default:"auto"},height:{type:String,default:"350px"},loading:{type:Boolean,default:!1},options:{type:Object,default:()=>{}}},setup(s){let e=null;const{options:a}=ze(s),n=Oe(),r=()=>{e=De.init(n.value),e.setOption({...a.value})},i=()=>{e.setOption({...a.value}),e.resize()},t=()=>{e&&e.resize()};return Ie(()=>a.value,c=>{c&&i()},{deep:!0}),Le(()=>{window.removeEventListener("resize",t)}),Me(()=>{window.addEventListener("resize",t),r()}),{echart:n}}});function ke(s,e,a,n,r,i){const t=Ve;return be(),xe(t,{spinning:s.loading,class:"echarts-loading"},{default:Pe(()=>[Be("div",{ref:"echart",style:Te({width:s.width,height:s.height}),class:"timeline-echarts"},null,4)]),_:1},8,["spinning"])}const Je=Ue(je,[["render",ke]]);export{Je as C};
