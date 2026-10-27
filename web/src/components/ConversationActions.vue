<!-- 列表行的更多操作；仅提供现有删除功能，菜单本身不删除或切换聊天。 -->
<script setup lang="ts">
import { ref, useId } from "vue";
import LifecycleDelete from "./LifecycleDelete.vue";
/** 目标固定为所在列表项；禁用条件沿用页面写入限制。 */
defineProps<{ kind: "CHAT" | "TASK"; target: string; title: string; disabled: boolean }>();
/** 只有服务端确认删除后才向页面报告。 */
const emit = defineEmits<{ deleted: [] }>();
const id = useId();
const popup = ref<HTMLElement>(),
  trigger = ref<HTMLButtonElement>();
const deletion = ref<InstanceType<typeof LifecycleDelete>>();
/** 使用视口坐标定位原生顶层浮层，避免被滚动侧栏裁切；自动开关及Escape由popover负责。 */
function position(): void {
  if (!popup.value || !trigger.value) return;
  const bounds = trigger.value.getBoundingClientRect();
  popup.value.style.left = `${Math.max(8, Math.min(bounds.right - 208, window.innerWidth - 216))}px`;
  popup.value.style.top = `${Math.max(8, Math.min(bounds.bottom + 6, window.innerHeight - 80))}px`;
}
/** 先关闭菜单并归还焦点，再请求原有范围预览；绝不跳过确认直接删除。 */
function remove(): void {
  popup.value?.hidePopover();
  trigger.value?.focus();
  void deletion.value?.inspect();
}
</script>
<template>
  <div class="conversation-actions">
    <button
      ref="trigger"
      class="conversation-more"
      :disabled="disabled || deletion?.busy"
      :popovertarget="id"
      :aria-label="`${title}的更多操作`"
      title="更多操作"
      @click="position"
    >
      ⋯
    </button>
    <div :id="id" ref="popup" popover class="conversation-popup" :aria-label="`${title}的操作`">
      <button class="conversation-remove" :disabled="disabled || deletion?.busy" @click="remove">
        <svg aria-hidden="true" viewBox="0 0 24 24">
          <path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 10v7M14 10v7" />
        </svg>
        删除对话与旅行
      </button>
    </div>
    <LifecycleDelete
      ref="deletion"
      :kind="kind"
      :target="target"
      hide-trigger
      @deleted="emit('deleted')"
    />
  </div>
</template>
<style scoped>
.conversation-actions {
  flex: none;
  align-self: center;
  margin-right: 6px;
}
.conversation-more {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  padding: 0;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: #6e7d76;
  font-size: 22px;
  opacity: 0;
}
.conversation-more:hover {
  background: #dce5df;
  color: #254f40;
}
.conversation-actions:has(.conversation-popup:popover-open) .conversation-more {
  opacity: 1;
}
.conversation-popup {
  position: fixed;
  inset: auto;
  margin: 0;
  width: 208px;
  padding: 6px;
  border: 1px solid #e3e8e5;
  border-radius: 13px;
  background: #fff;
  box-shadow: 0 10px 32px #18382e24;
}
.conversation-remove {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 11px 9px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #bf3f40;
  text-align: left;
  font-size: 13px;
}
.conversation-remove:hover {
  background: #fff0f0;
}
.conversation-remove svg {
  width: 18px;
  height: 18px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}
@media (hover: none) {
  .conversation-more {
    opacity: 1;
  }
}
</style>
