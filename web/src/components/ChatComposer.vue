<!-- 统一自然语言输入：不要求填写完整字段；中文输入法回车不发送，Shift+Enter换行。 -->
<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import type { DocumentView } from "../workspace/documents";
import { documentLabel } from "../workspace/documents";
/** modelValue为父页面草稿；disabled只阻止提交，用户仍可编辑，输入不在本组件持久化。 */
const props = defineProps<{
  modelValue: string;
  documents?: DocumentView[];
  selectedDocuments?: string[];
  disabled: boolean;
  placeholder?: string;
  answering?: boolean;
  attachmentDisabled?: boolean;
  stopping?: boolean;
  stopDisabled?: boolean;
}>();
/** 仅显式提交触发业务命令，编辑及高度变化不发送网络请求。 */
const emit = defineEmits<{
  "update:modelValue": [value: string];
  submit: [];
  "update:selectedDocuments": [ids: string[]];
  attach: [];
  stop: [];
}>();
const input = ref<HTMLTextAreaElement>();
const caret = ref(0), mentionIndex = ref(0), dismissed = ref(false);
const selected = computed(() => props.selectedDocuments ?? []);
const selectedEntries = computed(() => selected.value.map(id => ({ id, document: props.documents?.find(d => d.id === id) })));
const blockedDocuments = computed(() => selected.value.some(id => !props.documents?.some(d => d.id === id && d.state === "READY")));
const mention = computed(() => {
  if (dismissed.value) return null;
  return /@([^@\n]*)$/.exec(props.modelValue.slice(0, caret.value));
});
const options = computed(() => (props.documents ?? []).filter(d => !selected.value.includes(d.id) && d.name.toLocaleLowerCase().includes((mention.value?.[1] ?? "").toLocaleLowerCase())));
/** 只移动本地光标状态；不发送请求或把文件ID写进文本。 */
function moveCaret(): void { caret.value = input.value?.selectionStart ?? props.modelValue.length; mentionIndex.value = 0; dismissed.value = false; }
/** @param document 用户明确选中的READY资料；同名通过上传列表顺序区分，不猜目标。 */
function chooseDocument(document: DocumentView): void {
  if (props.disabled || document.state !== "READY" || selected.value.length >= 10 || selected.value.includes(document.id)) return;
  const query = mention.value;
  if (query) {
    const start = caret.value - (query[1]?.length ?? 0) - 1;
    emit("update:modelValue", props.modelValue.slice(0, start) + props.modelValue.slice(caret.value));
  }
  emit("update:selectedDocuments", [...selected.value, document.id]);
  dismissed.value = true;
  void focus();
}
/** @param id 从本轮草稿解绑，不删除已上传原件或历史消息引用。 */
function removeDocument(id: string): void { if (props.disabled) return; emit("update:selectedDocuments", selected.value.filter(value => value !== id)); }
const remaining = computed(() => 4000 - props.modelValue.length);
watch(
  () => props.modelValue,
  () => {
    void nextTick(resize);
  },
);
/** 保持输入框适应内容；限定高度后在框内滚动，不挤走对话。 */
function resize(): void {
  const element = input.value;
  if (!element) return;
  element.style.height = "auto";
  element.style.height = `${Math.min(element.scrollHeight, 200)}px`;
}
/** @param event 用户原始输入；不在前端提取关键字段。 */
function update(event: Event): void {
  emit("update:modelValue", (event.target as HTMLTextAreaElement).value);
  moveCaret();
  resize();
}
/** 空白、超限、忙碌时不产生命令；网络未知结果由工作台原样重试。 */
function submit(): void {
  if (props.stopping || props.disabled || blockedDocuments.value || !props.modelValue.trim() || remaining.value < 0) return;
  emit("submit");
}
/** @param event 中文候选确认键和换行保留原生行为；只有正常Enter会提交。 */
function keydown(event: KeyboardEvent): void {
  if (event.isComposing || event.keyCode === 229) return;
  if (mention.value) {
    if (event.key === "Escape") { event.preventDefault(); dismissed.value = true; return; }
    if (["ArrowDown", "ArrowUp"].includes(event.key)) {
      event.preventDefault();
      mentionIndex.value = Math.max(0, Math.min(options.value.length - 1, mentionIndex.value + (event.key === "ArrowDown" ? 1 : -1)));
      return;
    }
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      const option = options.value[mentionIndex.value];
      if (option) chooseDocument(option);
      return;
    }
  }
  if (
    event.key !== "Enter" ||
    event.shiftKey ||
    event.isComposing ||
    event.keyCode === 229
  )
    return;
  event.preventDefault();
  submit();
}
/** 用户选择建议或回到对话时聚焦，不移动已有光标内容。 */
async function focus(): Promise<void> {
  await nextTick();
  input.value?.focus();
  resize();
}
defineExpose({ focus });
</script>
<template>
  <div class="composer-wrap">
    <form class="composer" @submit.prevent="submit">
      <div v-if="selected.length" class="file-chips" aria-label="本轮选中的资料">
        <div v-for="entry in selectedEntries" :key="entry.id" class="file-chip">
          <span aria-hidden="true">▤</span>
          <span><strong>{{ entry.document?.name ?? '资料正在同步' }}</strong><small>{{ entry.document ? documentLabel(entry.document) : '暂不可发送，请等待同步或移除' }}</small></span>
          <button type="button" :aria-label="`移除${entry.document?.name ?? '资料'}引用`" :disabled="disabled" @click="removeDocument(entry.id)">×</button>
        </div>
      </div>
      <div v-if="mention" class="file-mentions" role="listbox" aria-label="选择当前会话文件">
        <p>选择文件 · ↑↓ 选择，Enter 引用，Esc 关闭</p>
        <button v-for="(doc,index) in options" :key="doc.id" type="button" role="option" :aria-selected="index === mentionIndex" :disabled="doc.state !== 'READY' || selected.length >= 10 || disabled" @mousedown.prevent @click="chooseDocument(doc)">
          <span>▤ {{ doc.name }} <small>附件 {{ (documents?.findIndex(d => d.id === doc.id) ?? 0) + 1 }} · {{ documentLabel(doc) }}</small></span>
        </button>
        <p v-if="!options.length">没有匹配的文件。可先通过 ＋ 上传资料。</p>
      </div>
      <textarea
        ref="input"
        :value="modelValue"
        aria-label="旅行想法"
        rows="2"
        maxlength="4000"
        :placeholder="placeholder ?? '说说你的旅行想法，想到哪里说到哪里…'"
        @input="update"
        @click="moveCaret"
        @keyup.left="moveCaret"
        @keyup.right="moveCaret"
        @keydown="keydown"
      />
      <div class="composer-toolbar">
        <button class="attach-button" type="button" aria-label="上传旅行资料" title="上传旅行资料（PDF、DOCX、TXT、Markdown）" :disabled="attachmentDisabled" @click="emit('attach')">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>
        </button>
        <span>{{
          answering
            ? "回答这轮问题，也可以提出自己的想法"
            : "目的地、时间、同行人、预算…不用一次说全"
        }}</span>
        <div class="composer-send">
          <span v-if="remaining < 300" class="muted" aria-live="polite">{{
            remaining
          }}</span>
          <button
            v-if="stopping"
            class="send-button stop-button"
            type="button"
            :disabled="stopDisabled"
            aria-label="停止当前处理"
            title="停止当前处理（保留消息与草稿）"
            @click="emit('stop')"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor" stroke="none" /></svg>
          </button>
          <button
            v-else
            class="send-button"
            type="submit"
            :disabled="disabled || blockedDocuments || !modelValue.trim() || remaining < 0"
            aria-label="发送消息"
            title="发送（Enter）"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M12 19V5m-6 6 6-6 6 6" />
            </svg>
          </button>
        </div>
      </div>
    </form>
    <p class="composer-note">
      Enter 发送 · Shift + Enter 换行 · @ 引用资料 <span>行程由你确认后生效</span>
    </p>
  </div>
</template>

<style scoped>
.file-chips{display:flex;gap:8px;flex-wrap:wrap;padding:12px 16px 0}.file-chip{display:flex;align-items:center;gap:8px;border:1px solid #dfe7e4;border-radius:12px;padding:8px 10px;max-width:100%;background:#f7faf9}.file-chip strong{display:block;max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px}.file-chip small{display:block;font-size:11px;color:#64748b}.file-chip button{border:0;background:transparent;font-size:18px;cursor:pointer}.file-mentions{margin:8px 16px;border:1px solid #dfe7e4;border-radius:12px;max-height:240px;overflow:auto;background:white;padding:8px}.file-mentions p{font-size:12px;color:#64748b;margin:6px}.file-mentions button{display:block;width:100%;border:0;border-radius:8px;padding:10px;text-align:left;background:transparent;cursor:pointer}.file-mentions button[aria-selected=true]{background:#edf5f1}.file-mentions small{display:block;color:#64748b}.file-mentions button:disabled{opacity:.5;cursor:default}
</style>
