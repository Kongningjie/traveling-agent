<!-- 只读分析与逐项确认；自定义字段仅限当前建议范围，不允许批量盲采纳或直接接受行程。 -->
<script setup lang="ts">
import { ref, watch, onUnmounted } from "vue";
import { Api, ApiError } from "../workspace/client";
import { decodeAdvice, adviceFieldLabel, type AdviceView } from "../workspace/advice";
import ReportProblem from "./ReportProblem.vue";
import SourcePreview from "./SourcePreview.vue";
/** 当前已认证聊天，切换时丢弃所有旧响应和待确认内容。 */
const props = defineProps<{ chat: string; disabled?: boolean }>();
/** 服务端变更已登记后刷新原聊天/旅行视图。 */
const emit = defineEmits<{ applied: [] }>();
/** 用户材料中的表述性质，仅用于展示；“材料称已订”不表示系统已确认预订。 */
const statementLabels: Record<string, string> = {
  BOOKED: "材料称已订",
  INTENDED: "打算",
  OPTIONAL: "可选",
  UNKNOWN: "性质未知",
};
const api = new Api(),
  items = ref<AdviceView[]>([]),
  error = ref(""),
  busy = ref(false),
  selected = ref<AdviceView | null>(null),
  action = ref(""),
  fields = ref<Record<string, string>>({});
let generation = 0;
/** 未知网络结果保存同一建议和正文，不能生成另一条采纳请求。 */
let retry: { id: string; action: string; changes: Record<string, string> } | null = null;
const retryable = ref(false);
/** 只轮询建议，不自动发送确认；迟到数据不能跨聊天展示。 */
async function refresh(): Promise<void> {
  if (!props.chat || busy.value) return;
  const stamp = generation,
    chat = props.chat;
  try {
    const result = decodeAdvice(await api.get<unknown>(`/api/v1/chats/${chat}/advice`));
    if (stamp === generation) {
      items.value = result;
      if (
        selected.value &&
        !result.some((x) => x.id === selected.value?.id && x.state === "OPEN")
      ) {
        selected.value = null;
      }
    }
  } catch {
    if (stamp === generation) error.value = "建议暂时无法同步，请刷新后重试。";
  }
}
/** 清理私有展示及等待确认，不保留旧文件派生内容。 */
function clear(): void {
  generation++;
  items.value = [];
  selected.value = null;
  fields.value = {};
  retry = null;
  retryable.value = false;
  error.value = "";
  busy.value = false;
}
watch(
  () => props.chat,
  () => {
    clear();
    void refresh();
  },
  { immediate: true },
);
/**
 * @param event 来源删除后立即撤掉原文、建议及待确认，随后从服务端重读。
 */
function invalidated(event: Event): void {
  if ((event as CustomEvent).detail === props.chat) {
    clear();
    void refresh();
  }
}
window.addEventListener("travel:sources-invalidated", invalidated);
const timer = setInterval(() => void refresh(), 2000);
onUnmounted(() => {
  clear();
  clearInterval(timer);
  window.removeEventListener("travel:sources-invalidated", invalidated);
});
/**
 * @param item 服务端建议
 * @param kind 明确选择，首次点击只展示确认卡，不发送写请求。
 */
function choose(item: AdviceView, kind: string): void {
  if (busy.value || props.disabled || retryable.value) return;
  selected.value = item;
  action.value = kind;
  fields.value = { ...item.draft.changes };
  error.value = "";
}
/** 二次确认后仅提交所见字段；无确认不会修改任何业务状态。 */
async function confirm(): Promise<void> {
  if (!selected.value || busy.value || props.disabled) return;
  await send({
    id: selected.value.id,
    action: action.value,
    changes: action.value === "CUSTOM" ? { ...fields.value } : {},
  });
}
/**
 * @param command 固定建议ID及字段；未知网络结果允许原样显式重试。
 */
async function send(command: {
  id: string;
  action: string;
  changes: Record<string, string>;
}): Promise<void> {
  const stamp = generation,
    chat = props.chat;
  busy.value = true;
  error.value = "";
  try {
    await api.post(`/api/v1/chats/${chat}/advice/${command.id}/actions`, {
      action: command.action,
      changes: command.changes,
      confirmed: true,
    });
    if (stamp === generation) {
      selected.value = null;
      retry = null;
      retryable.value = false;
      emit("applied");
    }
  } catch (failure) {
    if (stamp === generation) {
      retryable.value = failure instanceof ApiError && failure.status === 0;
      retry = retryable.value ? command : null;
      error.value = retryable.value
        ? "确认结果未知，可原样重试；请勿重新采纳其他建议。"
        : "建议可能已过期、存在未决问题或字段冲突。请核对状态，必要时重新分析。";
    }
  } finally {
    if (stamp === generation) {
      busy.value = false;
      void refresh();
    }
  }
}
/** 只重放原请求，不能创建额外变更身份。 */
async function retryConfirm(): Promise<void> {
  if (retry && !busy.value) await send(retry);
}
/**
 * @param state 持久结果
 * @returns 明确区分建议、变更收件与行程生效。
 */
function label(state: string): string {
  return (
    (
      {
        OPEN: "仅建议，尚未采纳",
        STALE: "依据已变化，请重新分析",
        APPLIED: "已登记变更，候选仍须接受",
        REJECTED: "已拒绝",
      } as Record<string, string>
    )[state] ?? state
  );
}
</script>
<template>
  <section v-if="items.length || error" class="advice-panel" aria-label="原计划分析建议">
    <h3>原计划分析与建议</h3>
    <p class="muted">
      用户资料不等于官方核实。采纳只会登记规划变更，经校验、审核并接受候选后才生效。
    </p>
    <p v-if="error" role="alert">{{ error }}</p>
    <button v-if="retryable" :disabled="busy" @click="retryConfirm">原样重试确认</button>
    <article v-for="item in items" :key="item.id" class="advice-card">
      <small
        >{{ label(item.state) }} · {{ statementLabels[item.draft.statement] }} ·
        {{ item.draft.evidence === "UNKNOWN" ? "待核实" : "仅用户材料" }}</small
      >
      <h4>{{ item.draft.problem }}</h4>
      <blockquote>{{ item.draft.quote }}</blockquote>
      <p>{{ item.draft.reason }}</p>
      <p><strong>建议：</strong>{{ item.draft.proposal }}</p>
      <p><strong>影响：</strong>{{ item.draft.impact }}</p>
      <p v-if="item.draft.pending">待确认：{{ item.draft.pending }}。请在聊天中补充后重新分析。</p>
      <ReportProblem kind="ADVICE" :scope="chat" :target="item.id" /><SourcePreview
        :chat="chat"
        :reference="item.draft.reference"
      />
      <dl v-if="Object.keys(item.draft.changes).length">
        <div
          v-for="(value, field) in item.state === 'APPLIED' ? item.applied : item.draft.changes"
          :key="field"
        >
          <dt>{{ adviceFieldLabel(String(field)) }}</dt>
          <dd>{{ value }}</dd>
        </div>
      </dl>
      <div v-if="item.state === 'OPEN'">
        <button
          :disabled="
            busy ||
            disabled ||
            retryable ||
            !Object.keys(item.draft.changes).length ||
            !!item.draft.pending
          "
          @click="choose(item, 'ADOPT')"
        >
          采纳这项建议
        </button>
        <button
          :disabled="
            busy ||
            disabled ||
            retryable ||
            !Object.keys(item.draft.changes).length ||
            !!item.draft.pending
          "
          @click="choose(item, 'CUSTOM')"
        >
          修改后采纳
        </button>
        <button :disabled="busy || disabled || retryable" @click="choose(item, 'REJECT')">
          拒绝
        </button>
      </div>
      <div v-if="selected?.id === item.id" class="advice-confirm">
        <p>
          {{
            action === "REJECT"
              ? "确认拒绝这项建议？"
              : "请再次核对原文、影响和以下字段。确认后登记变更，尚不会让行程生效。"
          }}
        </p>
        <label v-for="(_, key) in fields" :key="key"
          >{{ adviceFieldLabel(String(key))
          }}<input
            v-model="fields[key]"
            :readonly="action !== 'CUSTOM'"
            :disabled="busy"
            maxlength="500"
        /></label>
        <button :disabled="busy || disabled || retryable" @click="confirm">
          {{ action === "REJECT" ? "确认拒绝" : "确认并登记变更" }}</button
        ><button :disabled="busy" @click="selected = null">取消</button>
      </div>
    </article>
  </section>
</template>
<style scoped>
.advice-panel {
  max-width: 850px;
  margin: 20px auto;
  font-size: 14px;
  line-height: 1.7;
}
.advice-card {
  border: 1px solid #e2e8f0;
  border-radius: 14px;
  padding: 18px;
  margin: 14px 0;
  background: #fff;
}
.advice-card h4 {
  margin: 8px 0;
}
.advice-card blockquote {
  margin: 8px 0;
  padding: 8px 12px;
  border-left: 3px solid #94a3b8;
  background: #f8fafc;
  white-space: pre-wrap;
}
.advice-card pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.advice-card button {
  margin: 4px;
}
.advice-confirm {
  margin-top: 12px;
  padding: 12px;
  background: #f1f5f9;
  border-radius: 10px;
}
.advice-confirm label {
  display: block;
}
.advice-confirm input {
  display: block;
  width: 100%;
  margin: 4px 0 10px;
}
.muted,
small {
  color: #64748b;
}
</style>
