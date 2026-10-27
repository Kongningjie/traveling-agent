<!-- 删除前读取服务端影响清单；独立确认，不由聊天文本或模型推断授权。 -->
<script setup lang="ts">
import { ref, watch, onUnmounted, nextTick } from "vue";
import { Api } from "../workspace/client";
import { decodeDeletePreview, deletionSaved, type DeletePreview } from "../workspace/lifecycle";
/** 来源删除保留已确认事实；CHAT/TASK删除同时清理关联聊天和旅行。 */
const props = defineProps<{
  kind: "CHAT" | "TASK" | "DOCUMENT";
  target: string;
  hideTrigger?: boolean;
}>();
/** 删除提交后通知父级清除私有缓存；不宣称后台文件已清理完成。 */
const emit = defineEmits<{ deleted: [] }>();
const api = new Api(),
  preview = ref<DeletePreview | null>(null),
  busy = ref(false),
  error = ref("");
const confirmation = ref<HTMLDialogElement>();
let generation = 0,
  request = "";
let returnFocus: HTMLElement | null = null;
onUnmounted(() => {
  generation++;
});
watch(
  () => [props.kind, props.target],
  () => {
    generation++;
    preview.value = null;
    error.value = "";
    request = "";
    busy.value = false;
  },
);
/** 仅展示有界预览，不在此请求删除。 */
async function inspect(): Promise<void> {
  if (busy.value || preview.value) return;
  returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  const stamp = generation;
  busy.value = true;
  error.value = "";
  try {
    const value = decodeDeletePreview(
      await api.get<unknown>(
        `/api/v1/lifecycle/preview?kind=${props.kind}&target=${encodeURIComponent(props.target)}`,
      ),
      props.kind,
      props.target,
    );
    if (stamp === generation) {
      preview.value = value;
      request = crypto.randomUUID();
      await nextTick();
      if (stamp === generation) confirmation.value?.showModal();
    }
  } catch {
    if (stamp === generation) error.value = "无法读取删除范围，请刷新后重试。";
  } finally {
    if (stamp === generation) busy.value = false;
  }
}
/** 用户明确点击确认才提交；网络结果不明保留原幂等键，绝不自动重发。 */
async function confirm(): Promise<void> {
  if (busy.value || !preview.value) return;
  const stamp = generation;
  busy.value = true;
  error.value = "";
  try {
    const result = await api.post<unknown>("/api/v1/lifecycle/deletions", {
      request,
      kind: props.kind,
      target: props.target,
      revision: preview.value.revision,
      confirmed: true,
    });
    if (!deletionSaved(result, request)) throw new Error("DELETE_PROTOCOL");
    if (stamp === generation) {
      preview.value = null;
      emit("deleted");
    }
  } catch {
    if (stamp === generation)
      error.value = "删除尚未确认。可原样重试；若范围已变化，请取消后重新查看。";
  } finally {
    if (stamp === generation) busy.value = false;
  }
}
/** 取消仅关闭确认，不能撤销已经发送的删除。 */
function close(): void {
  if (busy.value) return;
  const stamp = generation;
  confirmation.value?.close();
  preview.value = null;
  request = "";
  error.value = "";
  void nextTick(() => {
    if (stamp === generation && !preview.value && returnFocus?.isConnected) returnFocus.focus();
  });
}
/** 列表菜单可打开同一预览流程；不暴露跳过确认的提交方法。 */
defineExpose({ inspect, busy });
</script>
<template>
  <span class="lifecycle-delete">
    <button v-if="!preview && !hideTrigger" :disabled="busy" @click="inspect">
      {{ kind === "DOCUMENT" ? "删除来源" : "删除对话与旅行" }}
    </button>
    <dialog
      v-if="preview"
      ref="confirmation"
      aria-label="确认删除"
      class="delete-preview"
      @cancel.prevent="close"
    >
      <h2>
        {{
          kind === "DOCUMENT"
            ? "确认删除此资料？"
            : kind === "TASK"
              ? "确认删除此旅行？"
              : "确认删除此对话？"
        }}
      </h2>
      <p>
        {{
          kind === "DOCUMENT" ? "删除后无法恢复。" : "关联的对话和旅行将一并删除，删除后无法恢复。"
        }}
      </p>
      <p v-if="error" role="alert">{{ error }}</p>
      <div class="delete-actions">
        <button class="delete-cancel" :disabled="busy" autofocus @click="close">取消</button
        ><button class="delete-confirm" :disabled="busy" @click="confirm">
          {{ busy ? "正在删除…" : "删除" }}
        </button>
      </div>
    </dialog>
    <span v-if="error && !preview" role="alert" :class="{ 'delete-toast': hideTrigger }">{{
      error
    }}</span>
  </span>
</template>
<style scoped>
.delete-preview {
  box-sizing: border-box;
  width: min(440px, calc(100vw - 32px));
  padding: 24px;
  border: 1px solid #e5e7eb;
  border-radius: 18px;
  background: #fff;
  color: #202522;
  font-size: 14px;
  max-height: 80vh;
  overflow: auto;
  box-shadow: 0 16px 60px #0002;
}
.delete-preview::backdrop {
  background: rgb(0 0 0 / 40%);
}
.delete-preview h2 {
  margin: 0 0 12px;
  font-size: 20px;
  font-weight: 600;
}
.delete-preview p {
  margin: 0 0 20px;
  line-height: 1.7;
  color: #626b65;
}
.delete-preview [role="alert"] {
  color: #a12a2a;
}
.delete-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 24px;
}
.delete-actions button {
  padding: 9px 18px;
  border-radius: 999px;
  font-size: 14px;
  font-weight: 600;
}
.delete-cancel {
  border: 1px solid #dce1dd;
  background: #fff;
  color: #29352e;
}
.delete-cancel:hover {
  background: #f4f6f4;
}
.delete-confirm {
  border: 1px solid #d53939;
  background: #d53939;
  color: #fff;
}
.delete-confirm:hover {
  background: #bc2929;
}
.delete-actions button:disabled {
  opacity: 0.55;
  cursor: wait;
}
.lifecycle-delete > span {
  display: block;
  color: #a12a2a;
  font-size: 13px;
}
.delete-toast {
  position: fixed;
  bottom: 20px;
  right: 20px;
  max-width: min(320px, calc(100vw - 40px));
  padding: 12px;
  background: #fff4f4;
  border: 1px solid #f1baba;
  border-radius: 10px;
  z-index: 50;
}
</style>
