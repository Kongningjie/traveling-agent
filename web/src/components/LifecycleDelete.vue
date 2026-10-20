<!-- 删除前读取服务端影响清单；独立确认，不由聊天文本或模型推断授权。 -->
<script setup lang="ts">
import { ref, watch, onUnmounted, nextTick } from "vue";
import { Api } from "../workspace/client";
import { decodeDeletePreview, deletionSaved, type DeletePreview } from "../workspace/lifecycle";
/** 来源删除保留已确认事实；CHAT/TASK删除同时清理关联聊天和旅行。 */
const props = defineProps<{kind: "CHAT" | "TASK" | "DOCUMENT"; target: string; hideTrigger?: boolean}>();
/** 删除提交后通知父级清除私有缓存；不宣称后台文件已清理完成。 */
const emit = defineEmits<{deleted: []}>();
const api = new Api(), preview = ref<DeletePreview | null>(null), busy = ref(false), error = ref("");
const confirmation = ref<HTMLDialogElement>();
let generation = 0, request = "";
let returnFocus: HTMLElement | null = null;
onUnmounted(() => {generation++;});
watch(() => [props.kind, props.target], () => {generation++; preview.value = null; error.value = ""; request = ""; busy.value = false;});
/** 仅展示有界预览，不在此请求删除。 */
async function inspect(): Promise<void> {
  if (busy.value || preview.value) return;
  returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  const stamp = generation; busy.value = true; error.value = "";
  try {const value = decodeDeletePreview(await api.get<unknown>(`/api/v1/lifecycle/preview?kind=${props.kind}&target=${encodeURIComponent(props.target)}`), props.kind, props.target); if (stamp === generation) {preview.value = value; request = crypto.randomUUID(); await nextTick(); if (stamp === generation) confirmation.value?.showModal();}}
  catch {if (stamp === generation) error.value = "无法读取删除范围，请刷新后重试。";}
  finally {if (stamp === generation) busy.value = false;}
}
/** 用户明确点击确认才提交；网络结果不明保留原幂等键，绝不自动重发。 */
async function confirm(): Promise<void> {
  if (busy.value || !preview.value) return;
  const stamp = generation; busy.value = true; error.value = "";
  try {const result = await api.post<unknown>("/api/v1/lifecycle/deletions", {request, kind: props.kind, target: props.target, revision: preview.value.revision, confirmed: true}); if (!deletionSaved(result, request)) throw new Error("DELETE_PROTOCOL"); if (stamp === generation) {preview.value = null; emit("deleted");}}
  catch {if (stamp === generation) error.value = "删除尚未确认。可原样重试；若范围已变化，请取消后重新查看。";}
  finally {if (stamp === generation) busy.value = false;}
}
/** 取消仅关闭确认，不能撤销已经发送的删除。 */
function close(): void {
  if (busy.value) return;
  const stamp = generation;
  confirmation.value?.close(); preview.value = null; request = ""; error.value = "";
  void nextTick(() => { if (stamp === generation && !preview.value && returnFocus?.isConnected) returnFocus.focus(); });
}
/** 列表菜单可打开同一预览流程；不暴露跳过确认的提交方法。 */
defineExpose({ inspect, busy });
</script>
<template>
  <span class="lifecycle-delete">
    <button v-if="!preview && !hideTrigger" :disabled="busy" @click="inspect">{{kind === 'DOCUMENT' ? '删除来源' : '删除对话与旅行'}}</button>
    <dialog v-if="preview" ref="confirmation" aria-label="删除影响确认" class="delete-preview" @cancel.prevent="close">
      <strong>请确认删除范围</strong><p>{{preview.notice}}</p>
      <p>附件 {{preview.documents}} 份；{{kind === 'DOCUMENT' ? '保留已确认行程版本' : `行程版本 ${preview.versions} 个`}}。共享长期偏好保留。</p>
      <ul><li v-for="item in preview.resources" :key="item.kind + item.id">{{item.name}} · {{item.id}}</li></ul>
      <p v-if="preview.truncated">清单仅展示前40项，确认将作用于上述全部数量。</p>
      <p>删除后立即不可读取；原件后台清理，最长24小时。此操作不可撤销。</p>
      <p v-if="error" role="alert">{{error}}</p>
      <button :disabled="busy" @click="confirm">确认删除</button><button :disabled="busy" @click="close">取消</button>
    </dialog>
    <span v-if="error && !preview" role="alert" :class="{ 'delete-toast': hideTrigger }">{{error}}</span>
  </span>
</template>
<style scoped>.delete-preview{width:min(600px,calc(100vw - 32px));padding:14px;border:1px solid #f1baba;border-radius:10px;background:#fff8f8;font-size:13px;max-width:600px;max-height:55vh;overflow:auto}.delete-preview::backdrop{background:rgb(15 23 42 / 35%)}.delete-preview p{margin:8px 0;line-height:1.6}.delete-preview li{overflow-wrap:anywhere}.lifecycle-delete>span{display:block;color:#a12a2a;font-size:13px}.delete-toast{position:fixed;bottom:20px;right:20px;max-width:min(320px,calc(100vw - 40px));padding:12px;background:#fff4f4;border:1px solid #f1baba;border-radius:10px;z-index:50}</style>
