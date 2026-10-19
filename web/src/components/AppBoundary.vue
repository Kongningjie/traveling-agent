<!-- 渲染失败显示可恢复入口，诊断上传只含固定类别，不发送组件堆栈及私有状态。 -->
<script setup lang="ts">
import {ref,onErrorCaptured} from "vue";
import App from "../App.vue";
import {reportClient} from "../workspace/diagnostics";
const failed=ref(false),problem=ref("");
/** 不读取异常对象；阻止失效子树继续渲染，只有真实保存才显示问题编号。 */
onErrorCaptured(()=>{failed.value=true;void reportClient("RENDER").then(id=>{problem.value=id;});return false;});
/** 刷新重新恢复服务端会话，不自动重放未知写请求。 */
function reload():void{window.location.reload();}
</script>
<template><main v-if="failed" role="alert" class="boundary"><h1>页面暂时无法显示</h1><p>请刷新页面恢复。未确认的操作不会自动重发。</p><p v-if="problem">问题编号：{{problem}}</p><p v-else>诊断未确认保存，可向维护者说明发生时间。</p><button @click="reload">刷新页面</button></main><App v-else/></template>
<style scoped>.boundary{max-width:680px;margin:15vh auto;padding:24px;font-family:system-ui}</style>
