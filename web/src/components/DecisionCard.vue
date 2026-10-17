<!-- 版本化决策卡片：推荐仅展示，部分回答与有限授权必须由用户显式提交。 -->
<script setup lang="ts">
import { computed, ref } from "vue";
import type { Card } from "../workspace/types";
const props = defineProps<{ card: Card; enabled: boolean }>();
/** 每次提交带明确动作及用户实际选择，不因推荐或挂载自动发送。 */
const emit = defineEmits<{
  answer: [
    action: string,
    selected: Record<string, string>,
    text: string,
    authorization: string[],
  ];
}>();
const selected = ref<Record<string, string>>({}),
  text = ref(""),
  authorized = ref<string[]>([]);
const choices = computed(() =>
  Object.fromEntries(
    Object.entries(selected.value).filter(([, value]) => value),
  ),
);
/** @param action 明确点击的动作；暂未决定不夹带选项，授权仅限本次勾选的问题。 */
function submit(action: string): void {
  emit(
    "answer",
    action,
    action === "UNDECIDED" ? {} : choices.value,
    action === "UNDECIDED" ? "" : text.value,
    action === "AUTHORIZE" ? [...authorized.value] : [],
  );
}
</script>
<template>
  <section class="decision" aria-label="需要你决定">
    <h3>一起确定这几件事</h3>
    <p class="muted">推荐仅供参考。可以只回答一部分，也可以给出自己的想法。</p>
    <fieldset :disabled="!enabled">
      <div
        v-for="question in card.decision.remaining"
        :key="question.id"
        class="question"
      >
        <label :for="`q-${question.id}`">{{ question.prompt }}</label>
        <p>{{ card.specs[question.id]?.reason }}</p>
        <p class="muted">{{ card.specs[question.id]?.tradeoff }}</p>
        <select
          v-if="Object.keys(question.options).length"
          :id="`q-${question.id}`"
          v-model="selected[question.id]"
        >
          <option value="">请选择（尚未采纳）</option>
          <option v-for="(label, id) in question.options" :key="id" :value="id">
            {{ label }}{{ id === question.recommendedOption ? " · 推荐" : "" }}
          </option>
        </select>
        <label
          v-if="card.decision.allowedAuthorizationScope.includes(question.id)"
          class="inline"
        >
          <input v-model="authorized" type="checkbox" :value="question.id" />
          授权系统在这个问题范围内决定
        </label>
      </div>
      <label>
        我的补充
        <textarea
          v-model="text"
          rows="3"
          maxlength="4000"
          placeholder="可以用自己的话说明"
        />
      </label>
      <div class="actions">
        <button
          :disabled="!Object.keys(choices).length && !text.trim()"
          @click="submit('ANSWER')"
        >
          提交回答
        </button>
        <button
          class="secondary"
          :disabled="!authorized.length"
          @click="submit('AUTHORIZE')"
        >
          提交有限授权
        </button>
        <button
          class="secondary"
          :disabled="!Object.keys(choices).length"
          @click="submit('REJECT')"
        >
          不采纳所选建议
        </button>
        <button class="quiet" @click="submit('UNDECIDED')">暂未决定</button>
      </div>
    </fieldset>
    <p v-if="!enabled" class="muted">正在同步或卡片已过期，请等待最新状态。</p>
  </section>
</template>
