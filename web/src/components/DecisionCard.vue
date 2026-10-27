<!-- 对话内的版本化追问：推荐不预选；自由回答使用主聊天框，选项与有限授权必须显式提交。 -->
<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { Card } from "../workspace/types";
/** 卡片及可回答资格来自一致的服务端视图，不在组件内自行判定权限。 */
const props = defineProps<{ card: Card; enabled: boolean }>();
/** selection只同步未提交草稿；只有answer事件才表示用户提交，reply只聚焦聊天框。 */
const emit = defineEmits<{
  answer: [action: string, selected: Record<string, string>, text: string, authorization: string[]];
  selection: [selected: Record<string, string>];
  reply: [];
}>();
const selected = ref<Record<string, string>>({}),
  authorized = ref<string[]>([]);
const choices = computed(() =>
  Object.fromEntries(Object.entries(selected.value).filter(([, value]) => value)),
);
watch(choices, (value) => emit("selection", { ...value }));
/**
 * @param question 当前问题
 * @param option 用户点选项；再次点击取消，不自动采纳推荐。
 */
function toggle(question: string, option: string): void {
  if (!props.enabled) return;
  selected.value = {
    ...selected.value,
    [question]: selected.value[question] === option ? "" : option,
  };
}
/**
 * @param action 明确动作；自由文本由主聊天框提交，授权不能由选择建议推断。
 */
function submit(action: string): void {
  if (!props.enabled) return;
  emit(
    "answer",
    action,
    action === "UNDECIDED" ? {} : { ...choices.value },
    "",
    action === "AUTHORIZE" ? [...authorized.value] : [],
  );
}
/** 父页面确认提交成功且卡片仍为原版本时清除已提交草稿，不影响后端决策状态。 */
function reset(): void {
  selected.value = {};
  authorized.value = [];
}
defineExpose({ reset });
</script>
<template>
  <section class="decision" aria-label="需要你决定">
    <div class="decision-heading">
      <span class="assistant-mark" aria-hidden="true">行</span>
      <h3>有几件事，想听听你的想法</h3>
    </div>
    <fieldset :disabled="!enabled">
      <div v-for="question in card.decision.remaining" :key="question.id" class="question">
        <p class="question-title">{{ question.prompt }}</p>
        <p v-if="card.specs[question.id]?.reason" class="muted">
          {{ card.specs[question.id]?.reason }}
        </p>
        <p v-if="card.specs[question.id]?.tradeoff" class="muted">
          {{ card.specs[question.id]?.tradeoff }}
        </p>
        <div class="option-chips" :role="'group'" :aria-label="question.prompt">
          <button
            v-for="(label, id) in question.options"
            :key="id"
            type="button"
            class="option-chip"
            :class="{ chosen: selected[question.id] === id }"
            :aria-pressed="selected[question.id] === id"
            @click="toggle(question.id, String(id))"
          >
            {{ label
            }}<span v-if="id === question.recommendedOption" class="recommend-label">推荐</span>
          </button>
        </div>
      </div>
      <p class="muted">可以只选一部分，也可以直接在下面的聊天框回答。</p>
      <div class="actions">
        <button :disabled="!Object.keys(choices).length" @click="submit('ANSWER')">提交选择</button>
        <button class="quiet" @click="emit('reply')">用自己的话回答 ↗</button>
      </div>
      <details class="decision-more">
        <summary>暂不决定或设置授权</summary>
        <label v-for="id in card.decision.allowedAuthorizationScope" :key="id" class="inline">
          <input v-model="authorized" type="checkbox" :value="id" />仅授权系统决定：{{
            card.decision.remaining[id]?.prompt ?? "当前问题"
          }}
        </label>
        <div class="actions">
          <button class="secondary" :disabled="!authorized.length" @click="submit('AUTHORIZE')">
            确认有限授权
          </button>
          <button class="quiet" :disabled="!Object.keys(choices).length" @click="submit('REJECT')">
            不采纳所选建议
          </button>
          <button class="quiet" @click="submit('UNDECIDED')">暂未决定</button>
        </div>
      </details>
    </fieldset>
    <p v-if="!enabled" class="muted">正在同步最新问题，稍后就能继续。</p>
  </section>
</template>
