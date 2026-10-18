<!-- 统一自然语言输入：不要求填写完整字段；中文输入法回车不发送，Shift+Enter换行。 -->
<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
/** modelValue为父页面草稿；disabled只阻止提交，用户仍可编辑，输入不在本组件持久化。 */
const props = defineProps<{
  modelValue: string;
  disabled: boolean;
  placeholder?: string;
  answering?: boolean;
}>();
/** 仅显式提交触发业务命令，编辑及高度变化不发送网络请求。 */
const emit = defineEmits<{
  "update:modelValue": [value: string];
  submit: [];
}>();
const input = ref<HTMLTextAreaElement>();
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
  resize();
}
/** 空白、超限、忙碌时不产生命令；网络未知结果由工作台原样重试。 */
function submit(): void {
  if (props.disabled || !props.modelValue.trim() || remaining.value < 0) return;
  emit("submit");
}
/** @param event 中文候选确认键和换行保留原生行为；只有正常Enter会提交。 */
function keydown(event: KeyboardEvent): void {
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
      <textarea
        ref="input"
        :value="modelValue"
        aria-label="旅行想法"
        rows="2"
        maxlength="4000"
        :placeholder="placeholder ?? '说说你的旅行想法，想到哪里说到哪里…'"
        @input="update"
        @keydown="keydown"
      />
      <div class="composer-toolbar">
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
            class="send-button"
            type="submit"
            :disabled="disabled || !modelValue.trim() || remaining < 0"
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
      Enter 发送 · Shift + Enter 换行 <span>行程由你确认后生效</span>
    </p>
  </div>
</template>
