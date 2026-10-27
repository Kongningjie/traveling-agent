<!-- 行程只读时间线；稳定ID绑定锁定操作，所有内容转义展示，不执行模型HTML。 -->
<script setup lang="ts">
import { computed } from "vue";
import type { Trip } from "../workspace/types";
const props = defineProps<{
  trip: Trip;
  title: string;
  lockedIds?: string[];
  editable?: boolean;
}>();
/** 用户主动更改锁定，经后端需求变更处理，不直接改快照。 */
const emit = defineEmits<{ lock: [id: string, locked: boolean] }>();
const days = computed(() => [...props.trip.days].sort((a, b) => a.date.localeCompare(b.date)));
/**
 * @param value 带时区时间；只显示服务端给出的本地日期时间，不猜测缺失时刻。
 */
function time(value: string | null | undefined): string {
  return value
    ? value
        .replace("T", " ")
        .replace(/\[.*\]$/, "")
        .replace(/\+08:00$/, "")
        .slice(0, 16)
    : "时间未知";
}
/**
 * @param id 城市停留身份
 * @returns 对应城市名称，缺失时明确未知。
 */
function city(id: string): string {
  return props.trip.cityStays.find((c) => c.id === id)?.cityId ?? "城市待核实";
}
/**
 * @param date 日期
 * @returns 不修改原快照的当日活动顺序。
 */
function items(date: string) {
  return props.trip.items
    .filter((i) => i.startsAt?.slice(0, 10) === date)
    .slice()
    .sort((a, b) => a.startsAt.localeCompare(b.startsAt));
}
</script>
<template>
  <section class="itinerary" :aria-label="title">
    <h3>{{ title }}</h3>
    <p>
      {{ trip.window.cityIds.join(" → ") }} · {{ trip.window.startsOn }} 至
      {{ trip.window.endsOn }} · {{ trip.window.travelers }}人
    </p>
    <p v-if="!trip.days.length" class="muted">尚未形成逐日安排。</p>
    <details v-for="(day, index) in days" :key="day.id" class="day" :open="index === 0">
      <summary>{{ day.date }}</summary>
      <article v-for="item in items(day.date)" :key="item.id" class="activity">
        <div class="row">
          <strong>{{ item.title }}</strong>
          <span class="tag">
            {{
              {
                ACTIVITY: "活动",
                MEAL: "用餐",
                REST: "休息",
                BUFFER: "缓冲",
                WAIT: "候车",
              }[item.type] ?? item.type
            }}
          </span>
        </div>
        <p>
          {{ city(item.cityStayId) }} · {{ time(item.startsAt) }} —
          {{ time(item.endsAt) }}
        </p>
        <p class="muted">地点：{{ item.locationKey || "待核实" }}</p>
        <span v-if="item.fixedArrangementId" class="tag">固定安排</span>
        <button
          v-if="editable"
          class="quiet"
          @click="emit('lock', item.id, !lockedIds?.includes(item.id))"
        >
          {{ lockedIds?.includes(item.id) ? "解除锁定" : "锁定此安排" }}
        </button>
        <span v-else-if="lockedIds?.includes(item.id)" class="tag">已锁定</span>
      </article>
    </details>
    <details v-if="trip.transportLegs.length" open>
      <summary>交通衔接</summary>
      <p v-for="leg in trip.transportLegs" :key="leg.id">
        {{ time(leg.startsAt) }} → {{ time(leg.endsAt) }} ·
        {{ leg.durationMinutes === null ? "时长未知" : `${leg.durationMinutes}分钟`
        }}{{ leg.estimated ? "（估算）" : "" }}
      </p>
    </details>
    <details v-if="trip.accommodationStays.length" open>
      <summary>住宿</summary>
      <p v-for="stay in trip.accommodationStays" :key="stay.id">
        {{ city(stay.cityStayId) }} · {{ stay.locationKey || "酒店待确认" }} ·
        {{ time(stay.checkIn) }} 至 {{ time(stay.checkOut) }} · {{ stay.rooms }}间
      </p>
    </details>
    <details v-if="trip.costEntries.length">
      <summary>费用明细（单价与口径）</summary>
      <p v-for="cost in trip.costEntries" :key="cost.id">
        {{ cost.category }}：{{ cost.lower }}～{{ cost.upper ?? "未知" }}元 · {{ cost.unit }} ×
        {{ cost.quantity }} {{ cost.paid ? "已支付" : "" }}
        {{ cost.includedInBudget ? "计入预算" : "不计入预算" }}
      </p>
    </details>
    <details v-if="trip.alternatives.length">
      <summary>局部备选</summary>
      <p v-for="alternative in trip.alternatives" :key="alternative.id">
        {{ alternative.description }}
      </p>
    </details>
  </section>
</template>
