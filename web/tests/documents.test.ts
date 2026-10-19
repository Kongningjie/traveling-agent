import test from "node:test";
import assert from "node:assert/strict";
import {decodeDocumentList,documentLabel} from "../src/workspace/documents.ts";
/** 合成状态夹具，不含真实文件或会话。 */
const fixture={id:"d40ba6da-7497-4e96-a002-01bfa4174df6",name:"计划.txt",kind:"txt",size:40,state:"QUEUED",code:"",sections:0,notice:"尚未读取"};
/** 排队及失败均不能显示已读取，只有服务端READY＋完整字段才通过。 */
test("document state reflects committed reading only",()=>{assert.equal(documentLabel(decodeDocumentList([fixture])[0]),"等待解析");const ready={...fixture,state:"READY",code:"OK",sections:2};assert.match(documentLabel(decodeDocumentList([ready])[0]),/已读取 2/);assert.throws(()=>decodeDocumentList([{...ready,sections:0}]));assert.throws(()=>decodeDocumentList([{...ready,code:"FAILED"}]));});
/** 路径、类型、数量及边界错误拒绝，不因类型断言忽略服务端协议差异。 */
test("document decoder rejects invalid identity and resource fields",()=>{for(const patch of [{id:"../secret"},{kind:"html"},{size:10485761},{sections:-1},{notice:"x".repeat(501)},{state:"UNKNOWN"},{size:"40"}])assert.throws(()=>decodeDocumentList([{...fixture,...patch}]));assert.throws(()=>decodeDocumentList(Array.from({length:11},()=>fixture)));assert.deepEqual(decodeDocumentList([]),[]);});
