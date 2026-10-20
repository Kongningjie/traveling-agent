import {test} from "node:test";
import assert from "node:assert/strict";
import {decodeDeletePreview, deletionSaved} from "../src/workspace/lifecycle.ts";
const target="00000000-0000-0000-0000-000000000001";
const fixture={kind:"DOCUMENT",target,revision:"a".repeat(64),documents:1,versions:2,notice:"已确认版本保留",resources:[{kind:"DOCUMENT",id:target,name:"<script>用户文件名</script>"}],truncated:false};
test("删除确认绑定精确对象，来源删除的保留说明及名称仅作为文本",()=>{assert.equal(decodeDeletePreview(fixture,"DOCUMENT",target).notice,"已确认版本保留");assert.throws(()=>decodeDeletePreview(fixture,"CHAT",target));});
test("超限清单、非法身份、负数量和缺失摘要拒绝显示确认",()=>{for(const changes of [{documents:-1},{resources:Array(41).fill(fixture.resources[0])},{revision:""},{resources:[{kind:"DOCUMENT",id:"../secret",name:"x"}]}])assert.throws(()=>decodeDeletePreview({...fixture,...changes},"DOCUMENT",target));});
test("只在原请求已保存且不可读时清理界面，不能把清理待完成写为文件已清除",()=>{assert.equal(deletionSaved({saved:true,id:target,readable:false,cleanupPending:true},target),true);for(const changes of [{saved:false},{readable:true},{id:"other"},{cleanupPending:null}])assert.equal(deletionSaved({saved:true,id:target,readable:false,cleanupPending:true,...changes},target),false);});
