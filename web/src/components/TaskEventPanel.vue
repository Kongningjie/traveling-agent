<!-- 本地只读事件验证面板；完整账号与旅行工作台尚未接入，不触发规划或真实服务。 -->
<script setup lang="ts">
import { onUnmounted, ref } from "vue";
import {
  applyEvent,
  cursorSequence,
  decodeEvent,
  type RunEvent,
  type TaskView,
} from "../events/runEvents";

const taskId = ref("");
const view = ref<TaskView | null>(null);
const recent = ref<RunEvent[]>([]);
const status = ref("输入已有任务标识，查看当前登录会话的执行事件。");
let source: EventSource | null = null;
let retry: ReturnType<typeof setTimeout> | null = null;
let epoch = 0;
let cursor = "";
let abort: AbortController | null = null;
let retryDelay = 1000;

/** 释放连接、重试和读取；不调用取消运行接口，epoch 使迟到响应失效。 */
function stop(): void {
  epoch += 1;
  source?.close();
  source = null;
  if (retry !== null) clearTimeout(retry);
  retry = null;
  abort?.abort();
  abort = null;
}

/** 读取单条一致性视图；身份失效或资源不可见时清除上一个用户的数据。 */
async function refresh(task: string, version: number): Promise<void> {
  const response = await fetch(`/api/v1/tasks/${task}/view`, {
    credentials: "same-origin",
    signal: abort?.signal,
  });
  if (version !== epoch) return;
  if ([401, 403, 404].includes(response.status)) {
    stop();
    view.value = null;
    recent.value = [];
    status.value = "请先登录，并确认该任务属于当前账号。";
    throw new Error("ACCESS_DENIED");
  }
  if (!response.ok) throw new Error("VIEW_UNAVAILABLE");
  const next = (await response.json()) as TaskView;
  if (version !== epoch) return;
  cursorSequence(task, next.cursor);
  if (next.taskId !== task) throw new Error("INVALID_VIEW");
  // 同一轮并发刷新也可能乱序返回；较旧 MVCC 视图不能覆盖已显示的较新视图。
  if (!view.value || cursorSequence(task, next.cursor) >= cursorSequence(task, view.value.cursor))
    view.value = next;
}

/** 从最后实际应用的事件继续读取；显式关闭原生自动重连，避免认证失败时无休止建立连接。 */
function connect(task: string, version: number): void {
  if (version !== epoch) return;
  const channel = new EventSource(
    `/api/v1/tasks/${task}/events/stream?after=${encodeURIComponent(cursor)}`,
  );
  source = channel;
  channel.onopen = () => {
    if (version === epoch) {
      status.value = "已连接";
      retryDelay = 1000;
    }
  };
  channel.addEventListener("run-event", (message: MessageEvent<string>) => {
    if (version !== epoch || !view.value) return;
    try {
      const event = decodeEvent(task, message.data, message.lastEventId);
      const result = applyEvent(view.value, cursor, event);
      view.value = result.view;
      cursor = result.cursor;
      if (result.applied) {
        recent.value = [...recent.value, event].slice(-20);
        void refresh(task, version).catch(() => {
          /* 定时重连仍会重读；不打印含用户数据的错误。 */
        });
      }
    } catch {
      channel.onerror?.(new Event("error"));
    }
  });
  channel.onerror = () => {
    channel.close();
    if (version !== epoch || retry !== null) return;
    status.value = "连接中断，正在恢复…";
    retry = setTimeout(async () => {
      retry = null;
      try {
        await refresh(task, version);
        if (version !== epoch || !view.value) return;
        if (cursorSequence(task, cursor) > cursorSequence(task, view.value.cursor)) {
          cursor = view.value.cursor;
          recent.value = []; // 数据恢复后的未来游标重新从一致视图起步。
        }
      } catch {
        if (version !== epoch) return;
      }
      if (version === epoch) connect(task, version);
    }, retryDelay);
    retryDelay = Math.min(15000, retryDelay * 2);
  };
}

/** 用户选择已有任务后重新读取快照与水位，页面不自动创建或执行业务任务。 */
async function watchTask(): Promise<void> {
  stop();
  view.value = null;
  recent.value = [];
  const task = taskId.value.trim().toLowerCase();
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(task)) {
    status.value = "请输入有效任务标识。";
    return;
  }
  const version = epoch;
  abort = new AbortController();
  status.value = "读取中…";
  try {
    await refresh(task, version);
    const initial = view.value as TaskView | null;
    if (version !== epoch || !initial) return;
    cursor = initial.cursor;
    retryDelay = 1000;
    connect(task, version);
  } catch {
    if (version === epoch) status.value = "暂时无法读取，请稍后重试。";
  }
}

onUnmounted(stop);
</script>

<template>
  <section class="panel" aria-label="本地事件验证">
    <h2>本地事件验证</h2>
    <p>完整旅行工作台尚未接入。此面板仅查看已有任务，不会发起规划。</p>
    <form @submit.prevent="watchTask">
      <label
        >任务标识 <input v-model="taskId" autocomplete="off" placeholder="已有任务的 UUID"
      /></label>
      <button type="submit">查看事件</button>
    </form>
    <p aria-live="polite">{{ status }}</p>
    <template v-if="view">
      <p>当前状态：{{ view.state ?? "尚未启动" }} · 执行代次：{{ view.generation }}</p>
      <p v-if="view.pendingInput">有待处理的修改。</p>
      <ul>
        <li v-for="event in recent" :key="event.id">
          {{ event.sequence }} · {{ event.type }} · {{ event.state ?? "历史事件" }}（代次
          {{ event.generation }}）
        </li>
      </ul>
    </template>
  </section>
</template>
