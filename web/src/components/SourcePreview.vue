<!-- 有界原文及引用预览；仅文本插值，切换聊天/文档立即丢弃迟到结果。 -->
<script setup lang="ts">
import { ref, watch, onUnmounted } from "vue";
import { Api } from "../workspace/client";
import { decodeSourceFragment as fragment } from "../workspace/sources";
import type { SourceFragment as Fragment } from "../workspace/sources";
/** 私有聊天及文档或消息ID；组件不接受服务端存储路径。 */
const props = defineProps<{ chat: string; document?: string; message?: string; reference?: string; revision?: string }>();
const api = new Api(), query = ref(""), fragments = ref<Fragment[]>([]), notice = ref(""), busy = ref(false);
let generation = 0;
/** @param event 同页删除完成信号；只携带逻辑聊天ID，立即清掉已打开的原文预览。 */
function invalidate(event: Event): void {
  if ((event as CustomEvent<string>).detail === props.chat) { generation++; fragments.value = []; notice.value = "资料已变化，旧引用已关闭，请重新核对。"; }
}
window.addEventListener("travel:sources-invalidated", invalidate);
onUnmounted(() => { generation++; window.removeEventListener("travel:sources-invalidated", invalidate); });
watch(() => [props.chat, props.document, props.message, props.reference, props.revision], () => { generation++; fragments.value = []; notice.value = ""; query.value = ""; });
/** 检索指定文件或按需查看成功回复实际读取的引用；没有引用不伪造来源。 */
async function load(): Promise<void> {
  if (busy.value) return;
  const stamp = generation, chat = props.chat; busy.value = true; notice.value = ""; fragments.value = [];
  try {
    let items: Fragment[], explanation: string;
    if (props.document) {
      if (!query.value.trim() || query.value.length > 80) throw new Error("SOURCE_QUERY_INVALID");
      const result = await api.post<{ fragments: unknown[]; notice: string; complete: boolean }>(`/api/v1/chats/${chat}/documents/${props.document}/search`, { query: query.value });
      if (!Array.isArray(result.fragments) || result.fragments.length > 5 || typeof result.notice !== "string" || result.notice.length > 500 || typeof result.complete !== "boolean") throw new Error("SOURCE_RESPONSE_INVALID");
      items = result.fragments.map(fragment); explanation = result.notice + (result.complete ? "" : " 返回达到上限，请缩小关键词范围。");
    } else if(props.reference) {
      items=[fragment(await api.get(`/api/v1/chats/${chat}/references/${props.reference}`))]; explanation="这项建议绑定的实际原文，用户材料并非官方核实。";
    } else {
      const ids = await api.get<unknown>(`/api/v1/chats/${chat}/messages/${props.message}/references`);
      if (!Array.isArray(ids) || ids.length > 10 || ids.some(id => typeof id !== "string" || !/^[0-9a-f-]{36}$/.test(id))) throw new Error("SOURCE_RESPONSE_INVALID");
      items = [];
      for (const id of ids) { if (stamp !== generation) return; items.push(fragment(await api.get(`/api/v1/chats/${chat}/references/${id}`))); }
      explanation = items.length ? "以下为本次回复实际读取的材料，不能据此宣称阅读全文或官方核实。" : "没有当前仍有效的文件引用；未读取、已删除或资料已变化。";
    }
    if (stamp === generation) { fragments.value = items; notice.value = explanation; }
  } catch { if (stamp === generation) notice.value = "原文暂不可读：可能尚未解析、引用已失效、资料已删除或查询超限。请刷新附件状态。"; }
  finally { busy.value = false; }
}
</script>
<template>
  <details class="source-preview"><summary>{{ document ? "检索原文" : "查看实际读取来源" }}</summary>
    <div v-if="document"><input v-model="query" maxlength="80" aria-label="文件原文关键词" placeholder="输入关键词，如：住宿、杭州东" @keydown.enter.prevent="load" /></div>
    <button :disabled="busy" @click="load">{{ busy ? "正在读取…" : document ? "检索" : "读取来源" }}</button>
    <p role="status">{{ notice }}</p>
    <article v-for="item in fragments" :key="item.reference">
      <small>原件 {{ item.locator.document }} · 第{{ item.locator.position }}页／段 · 字符{{ item.locator.start }}～{{ item.locator.end }}</small>
      <pre>{{ item.text }}</pre><p>{{ item.truncated ? "仅展示此页段的一部分。" : "已读取此页段，未代表全文。" }} {{ item.notice }}</p>
    </article>
  </details>
</template>
<style scoped>.source-preview{font-size:12px;margin:8px 0}.source-preview pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f8fafc;padding:10px;border-radius:8px}.source-preview input{max-width:100%;margin:8px 0}.source-preview article{border-top:1px solid #e5e7eb;padding-top:10px}.source-preview small{overflow-wrap:anywhere}</style>
