<!-- 账号入口收纳个人操作；原生popover支持外部点击、Escape关闭及焦点返回，不复制GPT中尚未实现的功能。 -->
<script setup lang="ts">
import { ref } from "vue";
/** 名称仅展示；忙碌时沿用原退出限制，菜单不承担权限判定。 */
defineProps<{ name: string; busy: boolean }>();
/** 仅发出用户选择，业务和私有数据读取由父页面负责。 */
const emit = defineEmits<{ preferences: []; feedback: []; logout: [] }>();
const popup = ref<HTMLElement>();
const trigger = ref<HTMLButtonElement>();
/** 先关闭非模态菜单，再交给父页面打开模态窗口，避免焦点层叠。
 * @param action 用户选择的现有功能，不代表新增服务端授权。
 */
function choose(action: "preferences" | "feedback" | "logout"): void {
  popup.value?.hidePopover();
  trigger.value?.focus();
  if (action === "preferences") emit("preferences");
  else if (action === "feedback") emit("feedback");
  else emit("logout");
}
</script>
<template>
  <div class="sidebar-account account-root">
    <div id="account-actions" ref="popup" popover class="account-popup" aria-label="用户操作">
      <p class="account-caption">{{ name }}</p>
      <button @click="choose('preferences')"><span aria-hidden="true">⚙</span>我的旅行偏好</button>
      <button @click="choose('feedback')"><span aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M5 4h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H9l-5 3v-3H3V6a2 2 0 0 1 2-2Z"/><path d="M7 9h10M7 13h6"/></svg></span>反馈与问题记录</button>
      <div class="account-divider" />
      <button :disabled="busy" @click="choose('logout')"><span aria-hidden="true">↪</span>退出登录</button>
    </div>
    <button ref="trigger" class="account-button" popovertarget="account-actions" title="打开用户菜单">
      <span class="avatar">{{ name.slice(0, 1).toUpperCase() }}</span>
      <span><strong>{{ name }}</strong><small>个人设置与帮助</small></span>
      <span aria-hidden="true">···</span>
    </button>
  </div>
</template>
<style scoped>
.account-button{anchor-name:--account-trigger;border-radius:12px;padding:10px;width:100%}
.account-button:hover{background:#e9eeeb}
.account-popup{position:fixed;position-anchor:--account-trigger;left:anchor(left);bottom:calc(anchor(top) + 10px);top:auto;right:auto;margin:0;width:260px;max-width:calc(100vw - 32px);padding:8px;border:1px solid #e4e8e5;border-radius:16px;box-shadow:0 12px 40px #18382e21;background:#fff;color:#294239}
.account-popup button{display:flex;align-items:center;gap:12px;width:100%;padding:12px;border:0;border-radius:9px;text-align:left;background:transparent;color:inherit;font-size:14px}
.account-popup button:hover{background:#f0f4f2}.account-popup button span{width:20px;text-align:center;font-size:19px}
.account-popup svg{width:19px;height:19px;fill:none;stroke:currentColor;stroke-width:1.5;stroke-linecap:round;stroke-linejoin:round}
.account-caption{margin:5px 12px 10px;color:#74837d;font-size:12px;overflow-wrap:anywhere}.account-divider{height:1px;background:#e8ece9;margin:6px}
@supports not (position-anchor:--account-trigger){.account-popup{left:16px;bottom:92px}}
</style>
