<!-- 私有附件交互；上传只表示保存，解析状态和清理由服务端决定，不执行文件内容。 -->
<script setup lang="ts">
import { computed, ref, watch, onUnmounted, nextTick } from "vue";
import ReportProblem from "./ReportProblem.vue";
import LifecycleDelete from "./LifecycleDelete.vue";
import SourcePreview from "./SourcePreview.vue";
import { Api } from "../workspace/client";
import { decodeDocumentList as decode, documentLabel as label } from "../workspace/documents";
import type { DocumentView } from "../workspace/documents";
/** 当前聊天可空；首个附件成功后由父页面打开新聊天。 */
const props = defineProps<{ chat: string; disabled?: boolean }>();
/** 不创建旅行占位任务，仅通知独立聊天身份。 */
const emit = defineEmits<{
  uploaded: [chat: string, document: string];
  analyze: [text: string, document: string];
  changed: [items: DocumentView[]];
}>();
const menu = ref<HTMLElement>(),
  picker = ref<HTMLInputElement>(),
  activeId = ref("");
const api = new Api(),
  items = ref<DocumentView[]>([]),
  error = ref(""),
  busy = ref(false),
  file = ref<File | null>(null);
let generation = 0,
  pendingId = "",
  pendingChat = "";
const active = computed(() => items.value.find((item) => item.id === activeId.value));
/** 只轮询状态，不自动重试上传／解析；切换聊天后的迟到响应不显示。 */
async function refresh(): Promise<void> {
  const stamp = generation,
    id = props.chat;
  if (!id || busy.value) return;
  try {
    const list = decode(await api.get<unknown>(`/api/v1/chats/${id}/documents`));
    if (stamp === generation) {
      items.value = list;
      emit("changed", list);
    }
  } catch {
    if (stamp === generation) error.value = "附件状态暂时无法同步，可稍后刷新。";
  }
}
watch(
  () => props.chat,
  () => {
    generation++;
    menu.value?.hidePopover();
    activeId.value = "";
    items.value = [];
    emit("changed", []);
    error.value = "";
    file.value = null;
    busy.value = false;
    pendingId = "";
    pendingChat = "";
    void refresh();
  },
  { immediate: true },
);
const timer = setInterval(() => void refresh(), 2000);
onUnmounted(() => {
  generation++;
  clearInterval(timer);
});
/**
 * 选择文件即上传到当前会话，不额外打开确认页面；上传不会自动调用模型。
 *
 * @param event 明确选择新文件才换幂等键；取消选择保留原待重试文件。
 */
function select(event: Event): void {
  const selected = (event.target as HTMLInputElement).files?.[0];
  if (!selected || busy.value || props.disabled) return;
  file.value = selected;
  pendingId = crypto.randomUUID();
  pendingChat = props.chat || crypto.randomUUID();
  error.value = "";
  (event.target as HTMLInputElement).value = "";
  void upload();
}
/** 用户明确打开选择器，选定后上传；取消文件选择不产生请求。 */
function choose(): void {
  if (!busy.value && !props.disabled) picker.value?.click();
}
/**
 * 打开单个文件的轻量操作菜单，不展示独立资料管理页面。
 *
 * @param id 当前会话文件；不存在时不显示旧文件菜单。
 */
async function manage(id: string): Promise<void> {
  const stamp = generation;
  activeId.value = id;
  await nextTick();
  if (stamp === generation && active.value) menu.value?.showPopover();
}
/**
 * 关闭文件菜单后才编辑分析草稿；不会自动采纳建议。
 *
 * @param text 给用户编辑的自然语言草稿
 * @param document 后台结构化绑定；不写入用户正文。
 */
function analyze(text: string, document: string): void {
  menu.value?.hidePopover();
  emit("analyze", text, document);
}
defineExpose({ choose, manage });
/** 请求未知时保留同文件／ID供用户显式重试，不自动发送。 */
async function upload(): Promise<void> {
  if (busy.value || props.disabled || !file.value) return;
  if (
    file.value.size === 0 ||
    file.value.size > 10 * 1024 * 1024 ||
    !/\.(pdf|docx|txt|md)$/i.test(file.value.name)
  ) {
    error.value = "支持文本PDF、DOCX、TXT、Markdown，每份不超过10MiB；不支持扫描件和OCR。";
    return;
  }
  const stamp = generation,
    id = pendingChat;
  busy.value = true;
  error.value = "";
  try {
    const csrf = await api.get<{ headerName: string; token: string }>("/api/v1/auth/csrf");
    const body = new FormData();
    body.append("id", pendingId);
    body.append("file", file.value);
    await api.request(`/api/v1/chats/${id}/documents`, {
      method: "POST",
      headers: { [csrf.headerName]: csrf.token },
      body,
    });
    if (stamp === generation) {
      file.value = null;
      emit("uploaded", id, pendingId);
    }
  } catch {
    if (stamp === generation)
      error.value =
        "上传未确认。可能超过容量／格式限制或连接中断；可用相同文件重试，服务端按原ID去重。";
  } finally {
    if (stamp === generation) {
      busy.value = false;
      void refresh();
    }
  }
}
/**
 * @param id 本聊天附件
 * @param action 明确操作；删除需用户确认，不通过LLM授权。
 */
async function act(id: string, action: string): Promise<void> {
  if (busy.value) return;
  busy.value = true;
  const stamp = generation;
  try {
    await api.post(`/api/v1/chats/${props.chat}/documents/${id}/actions`, { action });
    if (action === "DELETE")
      window.dispatchEvent(new CustomEvent("travel:sources-invalidated", { detail: props.chat }));
  } catch {
    if (stamp === generation) error.value = "操作尚未确认，请刷新状态后重试。";
  } finally {
    if (stamp === generation) {
      busy.value = false;
      void refresh();
    }
  }
}
/** 来源删除后清除所有打开的预览，并重新读取权威状态。 */
function removed(): void {
  window.dispatchEvent(new CustomEvent("travel:sources-invalidated", { detail: props.chat }));
  void refresh();
}
</script>
<template>
  <input
    ref="picker"
    class="file-picker"
    aria-label="选择旅行资料"
    type="file"
    accept=".pdf,.docx,.txt,.md"
    :disabled="busy || disabled"
    @change="select"
  />
  <div v-if="file" class="upload-card" role="status">
    <span class="document-icon" aria-hidden="true">{{
      file.name.split(".").pop()?.toUpperCase()
    }}</span>
    <span
      ><strong>{{ file.name }}</strong
      ><small>{{ busy ? "正在上传…" : "上传未完成" }}</small></span
    >
    <button v-if="!busy" :disabled="disabled" @click="upload">重试上传</button>
  </div>
  <p v-if="error" role="alert" class="upload-error">
    {{ error }} <button :disabled="busy" @click="refresh">刷新状态</button>
  </p>
  <div ref="menu" popover class="file-actions" aria-label="文件操作">
    <template v-if="active">
      <strong class="file-name">{{ active.name }}</strong>
      <small>{{ label(active) }}</small>
      <p v-if="active.notice" class="file-notice">{{ active.notice }}</p>
      <div v-if="active.state === 'READY'" class="action-list">
        <button
          :disabled="busy || disabled"
          @click="analyze('请解释这份旅行计划，先不要创建旅行。', active.id)"
        >
          解释计划
        </button>
        <button
          :disabled="busy || disabled"
          @click="analyze('请检查这份旅行计划的问题，并标明原文来源。先不修改旅行。', active.id)"
        >
          检查问题
        </button>
        <button
          :disabled="busy || disabled"
          @click="analyze('请给这份旅行计划一些优化建议，确认前不开始规划。', active.id)"
        >
          优化建议
        </button>
      </div>
      <SourcePreview
        v-if="active.state === 'READY'"
        :key="active.id"
        :chat="chat"
        :document="active.id"
      />
      <a :href="`/api/v1/chats/${chat}/documents/${active.id}/download`" download>下载文件</a>
      <button
        v-if="['QUEUED', 'RUNNING'].includes(active.state)"
        :disabled="busy"
        @click="act(active.id, 'STOP')"
      >
        停止解析
      </button>
      <button v-if="active.state === 'PAUSED'" :disabled="busy" @click="act(active.id, 'RETRY')">
        重试解析
      </button>
      <LifecycleDelete kind="DOCUMENT" :target="active.id" @deleted="removed" />
      <ReportProblem kind="DOCUMENT" :scope="chat" :target="active.id" />
    </template>
  </div>
</template>
<style scoped>
.file-picker {
  display: none;
}
.upload-card {
  display: flex;
  align-items: center;
  gap: 10px;
  max-width: 420px;
  padding: 12px;
  border: 1px solid #e1e5e2;
  border-radius: 14px;
  background: #fff;
  margin: 0 0 10px;
  font-size: 13px;
}
.upload-card > span:last-of-type {
  min-width: 0;
  flex: 1;
}
.upload-card strong {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.upload-card small {
  display: block;
  margin-top: 4px;
  color: #78837b;
}
.document-icon {
  display: grid;
  place-items: center;
  min-width: 40px;
  height: 44px;
  background: #fce9e7;
  color: #ba4940;
  border-radius: 9px;
  font-size: 10px;
  font-weight: 700;
}
.upload-error {
  font-size: 12px;
  color: #a12a2a;
  overflow-wrap: anywhere;
}
.file-actions {
  position: fixed;
  inset: auto;
  left: 50%;
  bottom: 160px;
  transform: translateX(-50%);
  box-sizing: border-box;
  width: min(330px, calc(100vw - 24px));
  max-height: 60vh;
  overflow: auto;
  margin: 0;
  padding: 16px;
  border: 1px solid #e4e7e5;
  border-radius: 16px;
  background: white;
  box-shadow: 0 10px 32px #18382e24;
  color: #27332d;
  font-size: 13px;
}
.file-name {
  display: block;
  overflow-wrap: anywhere;
  margin-bottom: 5px;
}
.file-actions small,
.file-notice {
  color: #78837b;
  line-height: 1.6;
}
.file-actions > a,
.file-actions > button {
  display: block;
  margin: 8px 0;
}
.action-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 12px 0;
}
.file-actions button,
.upload-card button,
.upload-error button {
  border: 1px solid #e1e5e2;
  border-radius: 9px;
  background: #fff;
  color: #526158;
  padding: 7px 10px;
  font-size: 12px;
}
</style>
