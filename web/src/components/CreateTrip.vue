<!-- 显式填写旅行范围；不根据人群标签推断偏好，前后端共同提示首版范围。 -->
<script setup lang="ts">
import { computed, reactive } from "vue";
import { tripError } from "../workspace/guards";
import type { Fact } from "../workspace/types";
defineProps<{ disabled: boolean }>();
/** 只提交用户实际填写且通过基本范围提示的字段。 */
const emit = defineEmits<{ create: [title: string, facts: Fact[]] }>();
const input = reactive({
  title: "",
  cities: "",
  startDate: "",
  endDate: "",
  travelers: 1,
  budgetRule: "NONE",
  budgetCny: "",
  budgetScope: [
    "ACTIVITIES",
    "MEALS",
    "LODGING",
    "INTERCITY",
    "ROUNDTRIP",
    "LOCAL_TRANSPORT",
  ],
  fixedStatus: "UNKNOWN",
});
const issue = computed(() =>
  tripError(input.cities, input.startDate, input.endDate, input.travelers),
);
/** 创建命令不混入空金额或假定已订状态；详细固定安排留给协商。 */
function submit(): void {
  if (issue.value) return;
  const fields: Record<string, string> = {
    cities: input.cities
      .split(/[,，]/)
      .map((v) => v.trim())
      .join(","),
    startDate: input.startDate,
    endDate: input.endDate,
    travelers: String(input.travelers),
    budgetRule: input.budgetRule,
    fixedStatus: input.fixedStatus,
  };
  if (input.budgetRule !== "NONE") {
    fields.budgetCny = input.budgetCny;
    fields.budgetScope = input.budgetScope.join(",");
  }
  emit(
    "create",
    input.title,
    Object.entries(fields).map(([field, value]) => ({
      field,
      value,
      quote: null,
    })),
  );
}
</script>
<template>
  <details class="create">
    <summary>＋ 创建新旅行</summary>
    <form @submit.prevent="submit">
      <fieldset :disabled="disabled">
        <label>
          旅行名称
          <input
            v-model="input.title"
            required
            maxlength="200"
            placeholder="例如：秋日城市漫游"
          />
        </label>
        <label>
          城市（1～3个）
          <input v-model="input.cities" required placeholder="北京，天津" />
        </label>
        <div class="row">
          <label>
            出发日期
            <input v-model="input.startDate" required type="date" />
          </label>
          <label>
            结束日期
            <input v-model="input.endDate" required type="date" />
          </label>
        </div>
        <label>
          同行人数
          <input
            v-model.number="input.travelers"
            required
            type="number"
            min="1"
          />
        </label>
        <label>
          预算口径
          <select v-model="input.budgetRule">
            <option value="NONE">不设上限</option>
            <option value="PER_GROUP">全组总额</option>
            <option value="PER_PERSON">每人金额</option>
          </select>
        </label>
        <template v-if="input.budgetRule !== 'NONE'">
          <label>
            人民币金额
            <input
              v-model="input.budgetCny"
              type="number"
              min="0.01"
              step="0.01"
              required
            />
          </label>
          <fieldset>
            <legend>预算包含哪些费用？</legend>
            <label
              v-for="(label, key) in {
                ACTIVITIES: '活动',
                MEALS: '餐饮',
                LODGING: '住宿',
                INTERCITY: '城际交通',
                ROUNDTRIP: '往返交通',
                LOCAL_TRANSPORT: '市内交通',
              }"
              :key="key"
              class="inline"
            >
              <input v-model="input.budgetScope" type="checkbox" :value="key" />
              {{ label }}
            </label>
          </fieldset>
        </template>
        <label>
          已订安排
          <select v-model="input.fixedStatus">
            <option value="UNKNOWN">尚待确认</option>
            <option value="NONE">没有固定安排</option>
          </select>
        </label>
        <p class="muted">
          {{ issue || "提交后共同核实信息，候选接受前不会生效。" }}
        </p>
        <button
          :disabled="
            !!issue ||
            (input.budgetRule !== 'NONE' && !input.budgetScope.length)
          "
          type="submit"
        >
          开始规划
        </button>
      </fieldset>
    </form>
  </details>
</template>
