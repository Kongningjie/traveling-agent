<!-- 主动反馈默认仅发送用户填写说明及目标ID，不复制回复、原文件或截图。 -->
<script setup lang="ts">
import {ref} from "vue";
import {feedbackCommand,savedProblem} from "../workspace/support";
import {Api} from "../workspace/client";
/** 父组件提供服务器已返回的目标，后端仍会重新校验归属。 */
const props=defineProps<{kind:"REPLY"|"DOCUMENT"|"ADVICE"|"RUN";scope:string;target:string}>();
const api=new Api(),open=ref(false),category=ref("OTHER"),description=ref(""),busy=ref(false),notice=ref(""),problem=ref("");
let command:unknown=null;
/** 网络结果未知时重放同一输入；用户需先明确新建反馈才能更换请求身份。 */
async function send():Promise<void>{if(busy.value)return;command??=feedbackCommand(crypto.randomUUID(),{kind:props.kind,scope:props.scope,id:props.target},category.value,description.value);busy.value=true;try{const result=await api.post<{saved:boolean;problem:string}>("/api/v1/support/feedback",command);if(savedProblem(result)){problem.value=savedProblem(result);notice.value="反馈已保存。";}else notice.value="未确认保存，请保留原请求重试。";}catch{notice.value="反馈未确认保存，可能连接中断、内容被拒绝或达到上限。可原样重试，或放弃后重新填写。";}finally{busy.value=false;}}
/** 明确放弃本次内容；不暗中更改未知写请求后重试。 */
function reset():void{command=null;description.value="";notice.value="";problem.value="";open.value=false;}
</script>
<template><div class="report-problem"><button class="link-button" @click="open=!open">报告问题</button><form v-if="open" @submit.prevent="send"><p>仅提交你填写的说明和目标编号。不会自动收集会话全文、文件或截图；请勿填写密码和密钥。</p><label>问题类型<select v-model="category" :disabled="busy||!!command"><option value="UNDERSTANDING">理解偏差</option><option value="INACCURATE">内容不准</option><option value="CONSTRAINT">遗漏约束</option><option value="UNUSABLE">无法操作</option><option value="STUCK">执行卡住</option><option value="OTHER">其他</option></select></label><textarea v-model="description" maxlength="1000" aria-label="问题说明" placeholder="可选，描述发生了什么" :disabled="busy||!!command"/><button :disabled="busy||!!problem">{{busy?"提交中…":problem?"已提交":command?"原样重试":"提交反馈"}}</button><button type="button" :disabled="busy" @click="reset">关闭并清空</button><p role="status">{{notice}} <span v-if="problem">问题编号：{{problem}}</span></p></form></div></template>
<style scoped>.report-problem{font-size:12px;margin:8px 0}.link-button{border:0;background:transparent;color:#64748b;cursor:pointer}form{padding:12px;border:1px solid #e2e8f0;border-radius:12px;background:#fff}textarea{display:block;width:100%;min-height:70px;margin:10px 0}p{line-height:1.6;overflow-wrap:anywhere}button{margin:4px}</style>
