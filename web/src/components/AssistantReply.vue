<!-- 助手回复使用开放正文与底部工具栏；复制原文只写本机剪贴板，反馈仍需用户主动提交。 -->
<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from "vue";
import ReplyInline from "./ReplyInline.vue";
import ReportProblem from "./ReportProblem.vue";
import SourcePreview from "./SourcePreview.vue";
import { replyBlocks } from "../workspace/reply";
/** 精确回复身份供现有反馈／来源接口重新鉴权，浏览器不改变已确认业务。 */
const props = defineProps<{
  text: string;
  chat: string;
  target: string;
  canRetry?: boolean;
  retryDisabled?: boolean;
}>();
/** 用户明确点击后由父页面重放原输入操作；展示组件不创建消息、不放宽预算或调用门禁。 */
const emit = defineEmits<{ retry: [] }>();
const blocks = computed(() => replyBlocks(props.text));
const copyNotice = ref("");
let generation = 0;
watch(
  () => [props.text, props.chat, props.target],
  () => {
    generation++;
    copyNotice.value = "";
  },
);
onUnmounted(() => {
  generation++;
});
/** 用户点击后复制完整原始回复；不复制页面、来源全文或认证信息，也不发送网络请求。 */
async function copy(): Promise<void> {
  const stamp = generation,
    text = props.text;
  try {
    await navigator.clipboard.writeText(text);
    if (stamp === generation) copyNotice.value = "已复制";
  } catch {
    if (stamp === generation) copyNotice.value = "复制失败，请选中文字手动复制。";
  }
}
</script>
<template>
  <article class="assistant-reply" aria-label="旅行助手回复">
    <div v-if="text" class="reply-body">
      <template v-for="(block, index) in blocks" :key="index">
        <div v-if="block.kind === 'code'" class="reply-code">
          <div class="code-caption">{{ block.language || "代码" }}</div>
          <pre><code>{{ block.text }}</code></pre>
        </div>
        <component
          :is="block.ordered ? 'ol' : 'ul'"
          v-else-if="block.kind === 'list'"
          :start="block.start"
          ><li v-for="(item, itemIndex) in block.items" :key="itemIndex">
            <ReplyInline :text="item" /></li
        ></component>
        <component
          :is="
            block.kind === 'heading'
              ? `h${block.level}`
              : block.kind === 'quote'
                ? 'blockquote'
                : 'p'
          "
          v-else
          ><ReplyInline :text="block.text"
        /></component>
      </template>
    </div>
    <p v-else class="empty-reply">尚无回复内容，可以重试处理这条消息。</p>
    <footer class="reply-actions" aria-label="回复操作">
      <button
        v-if="text"
        class="reply-tool"
        :aria-label="copyNotice === '已复制' ? '已复制回复' : '复制回复'"
        :title="copyNotice === '已复制' ? '已复制' : '复制回复'"
        @click="copy"
      >
        <svg v-if="copyNotice !== '已复制'" aria-hidden="true" viewBox="0 0 24 24">
          <rect x="8" y="4" width="12" height="13" rx="3" />
          <path d="M15 17v2a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2h3" />
        </svg>
        <svg v-else aria-hidden="true" viewBox="0 0 24 24"><path d="m5 12 4 4L19 6" /></svg>
      </button>
      <ReportProblem v-if="text" compact kind="REPLY" :scope="chat" :target="target" />
      <button
        v-if="canRetry"
        class="reply-tool"
        :disabled="retryDisabled"
        aria-label="重试这条消息"
        title="重新处理这条消息（沿用原消息和预算）"
        @click="emit('retry')"
      >
        <svg aria-hidden="true" viewBox="0 0 24 24">
          <path d="M20 7v5h-5M4 17v-5h5M6.1 7a7 7 0 0 1 11.8-1L20 9M4 15l2.1 3A7 7 0 0 0 17.9 17" />
        </svg>
      </button>
      <span v-if="copyNotice" class="copy-notice" role="status">{{ copyNotice }}</span>
    </footer>
    <SourcePreview
      v-if="text"
      :key="chat + target"
      :chat="chat"
      :message="target"
      :revision="text"
    />
  </article>
</template>
<style scoped>
.assistant-reply {
  margin: 30px 0 38px;
  color: #202725;
  min-width: 0;
}
.reply-body {
  font-size: 16px;
  line-height: 1.9;
  overflow-wrap: anywhere;
}
.reply-body p,
.reply-body blockquote {
  margin: 0 0 18px;
  white-space: pre-wrap;
}
.reply-body h2,
.reply-body h3,
.reply-body h4,
.reply-body h5,
.reply-body h6 {
  margin: 26px 0 14px;
  font-size: 19px;
  line-height: 1.65;
  font-weight: 650;
}
.reply-body > :first-child {
  margin-top: 0;
}
.reply-body > :last-child {
  margin-bottom: 0;
}
.reply-body ul,
.reply-body ol {
  padding-left: 25px;
  margin: 14px 0 20px;
}
.reply-body li {
  margin: 6px 0;
}
.reply-body blockquote {
  border-left: 3px solid #d5ded9;
  padding-left: 16px;
  color: #66726b;
}
.reply-body :deep(strong) {
  font-weight: 650;
}
.reply-body :deep(code) {
  font-family: Consolas, monospace;
  font-size: 0.88em;
  border-radius: 5px;
  background: #f0f2f1;
  padding: 2px 5px;
}
.reply-code {
  margin: 20px 0;
  border: 1px solid #e5e8e6;
  border-radius: 12px;
  overflow: hidden;
  background: #f8f9f8;
}
.code-caption {
  padding: 7px 14px;
  background: #eef1ee;
  color: #6d776f;
  font-size: 12px;
}
.reply-code pre {
  margin: 0;
  padding: 16px;
  overflow-x: auto;
  white-space: pre;
  line-height: 1.65;
  font-size: 13px;
}
.reply-code pre code {
  background: transparent;
  padding: 0;
  font-size: inherit;
}
.reply-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 3px;
  margin-top: 14px;
  min-height: 32px;
}
.reply-tool {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 7px;
  padding: 6px;
  background: transparent;
  color: #8a918c;
  cursor: pointer;
}
.reply-tool:hover {
  background: #eef1ef;
  color: #3d4941;
}
.reply-tool svg {
  width: 19px;
  height: 19px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.5;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.copy-notice {
  font-size: 12px;
  color: #708076;
  margin-left: 6px;
}
.reply-tool:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.empty-reply {
  color: #7d8881;
  font-size: 14px;
  line-height: 1.8;
}
@media (max-width: 600px) {
  .reply-body {
    font-size: 15px;
    line-height: 1.85;
  }
  .assistant-reply {
    margin: 26px 0 32px;
  }
  .reply-body h2,
  .reply-body h3 {
    font-size: 18px;
  }
}
</style>
