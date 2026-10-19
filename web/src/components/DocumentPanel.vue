<!-- 私有附件交互；上传只表示保存，解析状态和清理由服务端决定，不执行文件内容。 -->
<script setup lang="ts">
import { ref, watch, onUnmounted } from "vue";
import SourcePreview from "./SourcePreview.vue";
import { Api } from "../workspace/client";
import { decodeDocumentList as decode, documentLabel as label } from "../workspace/documents";
import type { DocumentView } from "../workspace/documents";
/** 当前聊天可空；首个附件成功后由父页面打开新聊天。 */
const props=defineProps<{chat: string;disabled?:boolean}>();
/** 不创建旅行占位任务，仅通知独立聊天身份。 */
const emit=defineEmits<{uploaded:[chat:string];analyze:[text:string]}>();
const api=new Api(),items=ref<DocumentView[]>([]),error=ref(""),busy=ref(false),file=ref<File|null>(null);
let generation=0,pendingId="",pendingChat="";
/** 只轮询状态，不自动重试上传／解析；切换聊天后的迟到响应不显示。 */
async function refresh(): Promise<void> {const stamp=generation,id=props.chat;if(!id || busy.value)return;try {const list=decode(await api.get<unknown>(`/api/v1/chats/${id}/documents`));if(stamp===generation)items.value=list;}catch {if(stamp===generation)error.value="附件状态暂时无法同步，可稍后刷新。";}}
watch(()=>props.chat,()=>{generation++;items.value=[];error.value="";file.value=null;pendingId="";pendingChat="";void refresh();},{immediate:true});
const timer=setInterval(()=>void refresh(),2000);onUnmounted(()=>{generation++;clearInterval(timer);});
/** @param event 文件仅保存在内存；明确选择新文件才换幂等键。 */
function select(event: Event): void {file.value=(event.target as HTMLInputElement).files?.[0]??null;pendingId=crypto.randomUUID();pendingChat=props.chat||crypto.randomUUID();error.value="";}
/** 请求未知时保留同文件／ID供用户显式重试，不自动发送。 */
async function upload(): Promise<void> {
  if(busy.value || props.disabled || !file.value)return;
  if(file.value.size===0 || file.value.size>10*1024*1024 || !/\.(pdf|docx|txt|md)$/i.test(file.value.name)){error.value="支持文本PDF、DOCX、TXT、Markdown，每份不超过10MiB；不支持扫描件和OCR。";return;}
  const stamp=generation,id=pendingChat;busy.value=true;error.value="";
  try {const csrf=await api.get<{headerName:string;token:string}>("/api/v1/auth/csrf");const body=new FormData();body.append("id",pendingId);body.append("file",file.value);await api.request(`/api/v1/chats/${id}/documents`,{method:"POST",headers:{[csrf.headerName]:csrf.token},body});if(stamp===generation){file.value=null;emit("uploaded",id);}}
  catch {if(stamp===generation)error.value="上传未确认。可能超过容量／格式限制或连接中断；可用相同文件重试，服务端按原ID去重。";}
  finally {busy.value=false;void refresh();}
}
/** @param id 本聊天附件 @param action 明确操作；删除需用户确认，不通过LLM授权。 */
async function act(id:string,action:string): Promise<void> {if(busy.value)return;if(action==="DELETE"&&!window.confirm("删除原附件及已提取正文？删除后立即不可读，后台清理原件。"))return;busy.value=true;const stamp=generation;try{await api.post(`/api/v1/chats/${props.chat}/documents/${id}/actions`,{action});if(action==="DELETE")window.dispatchEvent(new CustomEvent("travel:sources-invalidated",{detail:props.chat}));}catch{if(stamp===generation)error.value="操作尚未确认，请刷新状态后重试。";}finally{busy.value=false;void refresh();}}
</script>
<template>
  <details class="document-panel"><summary>旅行资料 <small>PDF / DOCX / TXT / Markdown · 单份10MiB</small></summary>
    <p class="muted">可先上传自己的计划。可让助手解释、检查或优化，建议需逐项确认后才登记变更；复杂表格需核对原件，不支持扫描件／OCR。</p>
    <div class="inline"><input aria-label="上传旅行资料" type="file" accept=".pdf,.docx,.txt,.md" :disabled="busy||disabled" @change="select"/><button :disabled="busy||disabled||!file" @click="upload">{{busy?"处理中…":"保存附件"}}</button><button :disabled="busy" @click="refresh">刷新</button></div>
    <p v-if="error" role="alert" class="error">{{error}}</p>
    <ul><li v-for="item in items" :key="item.id"><strong>{{item.name}}</strong> — {{label(item)}}<p>{{item.notice}}</p>
      <div v-if="item.state==='READY'"><button :disabled="busy||disabled" @click="emit('analyze',`请解释我上传的旅行计划，文件ID：${item.id}。只分析，先不要创建旅行。`)">解释计划</button><button :disabled="busy||disabled" @click="emit('analyze',`请检查这份旅行计划的问题并标明原文来源，文件ID：${item.id}。只分析，不修改旅行。`)">检查问题</button><button :disabled="busy||disabled" @click="emit('analyze',`请优化我的旅行计划，文件ID：${item.id}。给出可逐项确认的需求建议，确认前不开始规划。`)">优化建议</button></div>
      <SourcePreview v-if="item.state==='READY'" :key="item.id" :chat="chat" :document="item.id" />
      <a :href="`/api/v1/chats/${chat}/documents/${item.id}/download`" download>下载原件</a>
      <button v-if="['QUEUED','RUNNING'].includes(item.state)" :disabled="busy" @click="act(item.id,'STOP')">停止解析</button>
      <button v-if="item.state==='PAUSED'" :disabled="busy" @click="act(item.id,'RETRY')">重试解析</button>
      <button :disabled="busy" @click="act(item.id,'DELETE')">删除</button>
    </li></ul>
  </details>
</template>
<style scoped>.document-panel{margin:0 auto 12px;padding:10px 14px;max-width:850px;border:1px solid #e5e7eb;border-radius:14px;font-size:13px;background:#fff}.document-panel summary{cursor:pointer}.document-panel small{color:#64748b;margin-left:12px}.document-panel li{margin:12px 0}.document-panel button,.document-panel a{margin:4px}.document-panel p{margin:8px 0;line-height:1.6}</style>
