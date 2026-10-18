<!-- 自由对话为主的旅行助手；结构化详情按需展开，事实、追问和版本资格以服务端为权威。 -->
<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from "vue";
import PreferencePanel from "./components/PreferencePanel.vue";
import ChatComposer from "./components/ChatComposer.vue";
import DecisionCard from "./components/DecisionCard.vue";
import ItineraryPanel from "./components/ItineraryPanel.vue";
import {
  sourceLink,
  currentNeedsValidation,
  replyMode,
} from "./workspace/guards";
import { useWorkspace } from "./workspace/useWorkspace";
import type { Message, Requirement } from "./workspace/types";
const {
  user,
  tasks,
  selected,
  view,
  messages,
  card,
  candidate,
  versions,
  evidence,
  history,
  changes,
  busy,
  loading,
  ready,
  error,
  notice,
  connection,
  retryable,
  acceptable,
  answerable,
  current,
  taskOffset,
  versionOffset,
  initialize,
  login,
  logout,
  selectTask,
  refresh,
  createTask,
  newConversation,
  send,
  answer,
  accept,
  recheck,
  inspect,
  restore,
  lock,
  cancel,
  retryCommand,
  pageTasks,
  pageVersions,
} = useWorkspace();
const username = ref(""),
  password = ref(""),
  register = ref(false),
  message = ref(""),
  target = ref(""),
  showRestore = ref(false);
const sidebarOpen = ref(false),
  detailsOpen = ref(false),
  preferencesOpen = ref(false);
const detailsTab = ref("行程"),
  chosenOptions = ref<Record<string, string>>({});
const composer = ref<InstanceType<typeof ChatComposer>>(),
  decisionEditor = ref<InstanceType<typeof DecisionCard>>(),
  scrollArea = ref<HTMLElement>(),
  preferenceDialog = ref<HTMLDialogElement>();
let creating = false,
  submittedText = "";
/** 示例只填入草稿，不提交、不提取字段、不默认授权。 */
const starters = [
  {
    icon: "↗",
    title: "找个地方，放松几天",
    text: "想找个地方放松几天，目的地还没定，你能和我一起想想吗？",
    note: "没想好目的地，也可以开始",
  },
  {
    icon: "◎",
    title: "和家人慢慢玩",
    text: "想带家人出去走走，希望节奏轻松一些，有好吃的，也有休息的时间。",
    note: "从同行人的想法聊起",
  },
  {
    icon: "⌁",
    title: "串起几个喜欢的城市",
    text: "这次想去不止一个城市，喜欢当地美食和街巷，想一起理顺路线。",
    note: "路线与安排，一起慢慢完善",
  },
];
const taskTitle = computed(
  () =>
    tasks.value.find((t) => t.task.scope.taskId === selected.value)?.title ??
    "这趟旅行",
);
const mode = computed(() =>
  target.value
    ? ready.value
      ? "MESSAGE"
      : "SYNCING"
    : replyMode(view.value, card.value, ready.value),
);
const inputDisabled = computed(
  () =>
    busy.value ||
    retryable.value ||
    Boolean(selected.value && mode.value === "SYNCING"),
);
const active = computed(() =>
  ["QUEUED", "RUNNING"].includes(view.value?.state ?? ""),
);
const paused = computed(() =>
  ["PAUSED_QUOTA", "PAUSED_BUDGET", "FAILED"].includes(view.value?.state ?? ""),
);
const stoppable = computed(() =>
  Boolean(
    view.value?.runId &&
      [
        "QUEUED",
        "RUNNING",
        "WAITING_USER",
        "WAITING_ACCEPTANCE",
        "PAUSED_QUOTA",
        "PAUSED_BUDGET",
      ].includes(view.value?.state ?? ""),
  ),
);
/** 业务字段的可读名称，显示名称不改变后端规范值。 */
const fieldLabels: Record<string, string> = {
  cities: "目的地",
  startDate: "出发日期",
  endDate: "结束日期",
  travelers: "同行人数",
  budgetRule: "预算口径",
  budgetCny: "预算金额（元）",
  budgetScope: "预算包含项目",
  pace: "旅行节奏",
  diet: "饮食偏好",
  interests: "兴趣",
  hardRequirements: "必须满足的要求",
  fixedStatus: "已订安排状态",
  fixedArrangements: "固定安排",
  lockedIds: "锁定安排",
  changeTargets: "本次修改对象",
};
/** @param value 服务端状态 @returns 用户可理解的审核结果，不把未知当作通过。 */
function reportLabel(value: string | undefined): string {
  return value === "PASS"
    ? "已通过"
    : value === "FAIL"
      ? "未通过"
      : value === "UNKNOWN"
        ? "待核实"
        : "待执行";
}
const requirements = computed(
  () => (view.value?.requirements.requirements ?? []) as Requirement[],
);
const lockedIds = computed(() => {
  const value = requirements.value.find((r) => r.field === "lockedIds")?.value;
  return value && value !== "NONE" ? value.split(",") : [];
});
const status = computed(
  () =>
    ({
      QUEUED: "等待执行",
      RUNNING: "规划中",
      WAITING_USER: "等待你补充",
      WAITING_ACCEPTANCE: "候选待接受",
      PAUSED_BUDGET: "达到执行预算，已暂停",
      PAUSED_QUOTA: "外部调用受限，已暂停",
      FAILED: "执行未完成，可补充后继续",
      SUCCEEDED: "已完成",
      CANCELLED: "已停止",
      SUPERSEDED: "已被新要求取代",
    })[view.value?.state ?? ""] ?? "尚未开始",
);
/** 登录结束立即清除密码字段，不保留凭证重试闭包。 */
async function authenticate(): Promise<void> {
  try {
    await login(username.value, password.value, register.value);
  } finally {
    password.value = "";
  }
}
/** 保存自由输入，失败时保留文本供用户修订；已收到时清空。 */
async function submitMessage(): Promise<void> {
  if (!message.value.trim() || inputDisabled.value) return;
  const text = message.value,
    task = selected.value;
  if (!task) {
    creating = true;
    submittedText = text;
  }
  try {
    if (!task) await createTask(text);
    else if (mode.value === "ANSWER")
      await answer("ANSWER", { ...chosenOptions.value }, text, []);
    else
      await send(
        text,
        target.value
          ? [{ field: "changeTargets", value: target.value, quote: null }]
          : [],
      );
    if (
      !error.value &&
      message.value === text &&
      (selected.value === task || creating)
    )
      message.value = "";
    if (!error.value) {
      chosenOptions.value = {};
      decisionEditor.value?.reset();
    }
  } finally {
    creating = false;
    submittedText = "";
  }
}
/** @param action 明确动作 @param choices 用户选项 @param text 卡片附带原文 @param authorization 显式有限授权；失败保留选择，成功后清除本轮选择草稿。 */
async function submitDecision(
  action: string,
  choices: Record<string, string>,
  text: string,
  authorization: string[],
): Promise<void> {
  const before = card.value?.decision;
  await answer(action, choices, text, authorization);
  if (
    !error.value &&
    before?.id === card.value?.decision.id &&
    before?.revision === card.value?.decision.revision
  )
    decisionEditor.value?.reset();
}
/** @param text 示例草稿；点击只编辑和聚焦，不发请求。 */
function useStarter(text: string): void {
  message.value = text;
  void composer.value?.focus();
}
/** 开始新会话不取消旧任务；未知写结果应先处理，不能丢失原重试键。 */
function startConversation(): void {
  if (busy.value || retryable.value) return;
  newConversation();
  sidebarOpen.value = false;
  message.value = "";
  void nextTick(() => composer.value?.focus());
}
/** @param id 本人任务；切换只读取，不重新执行规划。 */
async function openTask(id: string): Promise<void> {
  sidebarOpen.value = false;
  await selectTask(id);
}
/** @param tab 用户主动展开的详情；候选到达时不抢占聊天。 */
function openDetails(tab: string): void {
  detailsTab.value = tab;
  detailsOpen.value = true;
}
/** 比较候选只读取快照，不自动接受或恢复。 */
async function inspectCandidate(): Promise<void> {
  if (candidate.value) {
    openDetails("版本");
    await inspect(candidate.value.candidate.version.snapshotId);
  }
}
/** 原生dialog圈定焦点，偏好在用户打开后才加载。 */
async function openPreferences(): Promise<void> {
  preferencesOpen.value = true;
  await nextTick();
  preferenceDialog.value?.showModal();
}
/** 关闭界面浮层不取消任何业务执行。 */
function closeOverlays(): void {
  sidebarOpen.value = false;
  detailsOpen.value = false;
  preferenceDialog.value?.close();
}
/** @param item 服务端消息 @returns 原文或明确字段摘要，不虚构助手回复。 */
function messageText(item: Message): string {
  return (
    item.text ||
    item.facts
      .map((f) => `${fieldLabels[f.field] ?? f.field}：${f.value}`)
      .join("；") ||
    "已提交选择或回答"
  );
}
/** @param requirement 权威字段 @returns 可读状态；未知依旧保留。 */
function requirementText(requirement: Requirement): string {
  if (requirement.field === "budgetRule")
    return (
      {
        NONE: "不设预算上限",
        PER_GROUP: "按整组计算",
        PER_PERSON: "按每人计算",
      }[requirement.value] ?? requirement.value
    );
  if (requirement.field === "fixedStatus")
    return (
      { NONE: "没有已订安排", UNKNOWN: "还待了解", PROVIDED: "已提供固定安排" }[
        requirement.value
      ] ?? requirement.value
    );
  return requirement.value;
}
/** 历史恢复前给出清楚的最终动作说明，仍需后续候选接受。 */
async function confirmRestore(): Promise<void> {
  const id = history.value?.summary.version.snapshotId;
  showRestore.value = false;
  if (id) await restore(id);
}
// 用户或任务改变时清空未提交草稿，防止上个账号的文本被新账号误提交。
watch(
  () => [user.value?.id, selected.value],
  (now, before) => {
    // 创建回执选择任务时，仅保留同账号在等待期间新写的草稿；跨账号必须全部清除。
    if (now[0] !== before[0] || !creating || message.value === submittedText)
      message.value = "";
    target.value = "";
    password.value = "";
    chosenOptions.value = {};
    detailsOpen.value = false;
    if (now[0] !== before[0]) {
      preferenceDialog.value?.close();
      preferencesOpen.value = false;
      sidebarOpen.value = false;
    }
  },
);
// 逐个监听业务标识；不能监听每次新建的数组，否则同版本轮询也会清空仍有效的选择草稿。
watch(
  [
    () => selected.value,
    () => card.value?.decision.id,
    () => card.value?.decision.revision,
  ],
  () => {
    chosenOptions.value = {};
  },
);
// 靠近底部或刚发送时跟随；后台轮询不会将阅读旧消息的用户拉走。
watch(
  [
    () => messages.value.length,
    () => card.value?.decision.id,
    () => card.value?.decision.revision,
  ],
  async () => {
    const element = scrollArea.value;
    if (!element) return;
    const follow =
      element.scrollHeight - element.scrollTop - element.clientHeight < 180 ||
      busy.value;
    await nextTick();
    if (follow) element.scrollTop = element.scrollHeight;
  },
);
// 当前任务、历史选择或服务端水位变化后重新确认，不能把旧确认意图用于新状态。
watch(
  [
    () => selected.value,
    () => history.value?.summary.version.snapshotId,
    () => view.value?.cursor,
  ],
  () => {
    showRestore.value = false;
  },
);
onMounted(initialize);
</script>
<template>
  <div
    class="app-shell"
    :class="{ authenticated: user }"
    @keydown.esc="closeOverlays"
  >
    <header v-if="!user" class="topbar">
      <a class="brand" href="/">行旅<span>旅行助手</span></a
      ><span class="muted">让想法，自由出发</span>
    </header>
    <main v-if="!user" class="welcome">
      <section>
        <p class="eyebrow">想去哪里，都可以从这里开始</p>
        <h1>把想法，慢慢变成旅程。</h1>
        <p>
          一个人、与家人，或和朋友同行。说说你的期待，一起比较、调整，再决定出发。
        </p>
        <p class="muted">
          首版支持1～3个城市、1～10天。所有候选均由你确认后生效。
        </p>
      </section>
      <form class="panel auth" @submit.prevent="authenticate">
        <h2>{{ register ? "创建账号" : "欢迎回来" }}</h2>
        <label>
          用户名
          <input
            v-model="username"
            required
            autocomplete="username"
            maxlength="64"
          />
        </label>
        <label>
          密码
          <input
            v-model="password"
            required
            type="password"
            :autocomplete="register ? 'new-password' : 'current-password'"
          />
        </label>
        <label class="inline">
          <input v-model="register" type="checkbox" />
          注册新账号并登录
        </label>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <button :disabled="busy" type="submit">
          {{ busy ? "请稍候…" : register ? "注册并登录" : "登录" }}
        </button>
      </form>
    </main>

    <main v-else class="chat-app" :class="{ 'sidebar-open': sidebarOpen }">
      <button
        v-if="sidebarOpen"
        class="sidebar-scrim"
        aria-label="关闭旅行列表"
        @click="sidebarOpen = false"
      />
      <aside id="trip-sidebar" class="chat-sidebar" aria-label="旅行会话">
        <div class="sidebar-brand">
          <span class="brand-symbol" aria-hidden="true">行</span
          ><strong>行旅<span>旅行，聊着来。</span></strong
          ><button
            class="icon-button mobile-only"
            aria-label="收起旅行列表"
            @click="sidebarOpen = false"
          >
            ×
          </button>
        </div>
        <button
          class="new-chat"
          :disabled="busy || retryable"
          @click="startConversation"
        >
          <span aria-hidden="true">＋</span>开启新旅行<span
            class="new-chat-hint"
            >↗</span
          >
        </button>
        <div class="sidebar-label">
          <span>我的旅行</span
          ><button
            class="icon-button"
            :disabled="busy"
            aria-label="刷新旅行列表"
            @click="pageTasks(0)"
          >
            ↻
          </button>
        </div>
        <nav class="task-list" aria-label="旅行任务">
          <button
            v-for="task in tasks"
            :key="task.task.scope.taskId"
            class="task-button"
            :class="{ active: selected === task.task.scope.taskId }"
            :disabled="busy || retryable"
            @click="openTask(task.task.scope.taskId)"
          >
            <span class="task-icon" aria-hidden="true">◷</span
            ><span
              ><strong>{{ task.title }}</strong
              ><small>{{
                task.task.currentVersionId ? "已有确认行程" : "一起完善中"
              }}</small></span
            >
          </button>
          <p v-if="!tasks.length" class="sidebar-empty">
            每段旅程，都从一句话开始。<br />聊聊你想去的地方吧。
          </p>
        </nav>
        <div v-if="taskOffset || tasks.length >= 20" class="sidebar-pagination">
          <button
            class="quiet"
            :disabled="busy || retryable || !taskOffset"
            @click="pageTasks(-1)"
          >
            上一页</button
          ><button
            class="quiet"
            :disabled="busy || retryable || tasks.length < 20"
            @click="pageTasks(1)"
          >
            下一页
          </button>
        </div>
        <div class="sidebar-account">
          <button class="account-button" @click="openPreferences">
            <span class="avatar">{{
              user.loginName.slice(0, 1).toUpperCase()
            }}</span
            ><span
              ><strong>{{ user.loginName }}</strong
              ><small>我的旅行偏好</small></span
            ><span aria-hidden="true">⚙</span></button
          ><button class="logout-button" :disabled="busy" @click="logout">
            退出登录
          </button>
        </div>
      </aside>
      <section class="chat-main" aria-label="旅行对话">
        <header class="chat-header">
          <div class="chat-heading">
            <button
              class="icon-button mobile-only"
              aria-label="展开旅行列表"
              aria-controls="trip-sidebar"
              :aria-expanded="sidebarOpen"
              @click="sidebarOpen = true"
            >
              ☰
            </button>
            <h1>{{ selected ? taskTitle : "新旅行" }}</h1>
            <span class="header-badge">旅行助手</span>
          </div>
          <button
            v-if="selected"
            class="quiet details-toggle"
            :aria-expanded="detailsOpen"
            aria-controls="trip-details"
            @click="detailsOpen = !detailsOpen"
          >
            行程与资料 <span aria-hidden="true">◫</span>
          </button>
        </header>
        <div
          ref="scrollArea"
          class="chat-scroll"
          :class="{ 'is-home': !selected }"
        >
          <section v-if="!selected" class="conversation-home">
            <div class="home-mark" aria-hidden="true">
              <svg viewBox="0 0 48 48">
                <path d="m31 16-4 11-11 5 5-12 10-4Z" />
                <circle cx="24" cy="24" r="20" />
                <circle cx="24" cy="24" r="2" />
              </svg>
            </div>
            <p class="home-eyebrow">每一种旅行想法，都值得聊聊</p>
            <h2>下一程，想去哪里？</h2>
            <p class="home-description">
              还没想好也没关系。说说你的期待，我们一起慢慢完善。
            </p>
            <ChatComposer
              ref="composer"
              v-model="message"
              :disabled="inputDisabled"
              @submit="submitMessage"
            />
            <div class="starter-grid">
              <button
                v-for="starter in starters"
                :key="starter.title"
                class="starter-card"
                :disabled="busy || retryable"
                @click="useStarter(starter.text)"
              >
                <span class="starter-icon" aria-hidden="true">{{
                  starter.icon
                }}</span
                ><strong>{{ starter.title }}</strong
                ><small>{{ starter.note }}</small>
              </button>
            </div>
            <p class="home-scope">
              支持 1–3 个城市 · 1–10 天 <span>先聊想法，再确定安排</span>
            </p>
          </section>
          <div v-else class="chat-thread">
            <p class="thread-start">
              这是属于你的旅行对话，随时可以补充或改变想法。
            </p>
            <article
              v-for="item in messages"
              :key="item.id"
              class="chat-message user-message"
            >
              <div class="message-bubble">
                <p>{{ messageText(item) }}</p>
              </div>
              <small>{{
                item.state === "APPLIED" ? "已记录" : "已保存"
              }}</small>
            </article>
            <p v-if="!messages.length" class="muted">正在同步这段旅行对话…</p>
            <div v-if="requirements.length" class="understood-note">
              <span class="assistant-mark" aria-hidden="true">行</span
              ><span
                >已记录
                {{ requirements.length }} 项旅行需求，你可以随时补充。</span
              ><button class="text-button" @click="openDetails('需求')">
                查看摘要 ↗
              </button>
            </div>
            <DecisionCard
              v-if="card && view?.state === 'WAITING_USER'"
              ref="decisionEditor"
              :key="`${selected}:${card.decision.id}:${card.decision.revision}`"
              :card="card"
              :enabled="answerable && !retryable"
              @answer="submitDecision"
              @selection="chosenOptions = $event"
              @reply="composer?.focus()"
            />
            <div v-if="active" class="run-note" role="status">
              <span class="status-dot pulsing" />{{
                view?.pendingInput ? "正在理解你新补充的想法" : status
              }}<span class="muted">完成后会在这里更新</span>
            </div>
            <div v-else-if="paused" class="run-note paused-note" role="status">
              <span class="status-dot" />
              <div>
                <strong>{{ status }}</strong>
                <p>你的想法已保存。可以继续补充，也可以稍后再来。</p>
              </div>
            </div>
            <div
              v-else-if="
                ['CANCELLED', 'SUPERSEDED'].includes(view?.state ?? '')
              "
              class="run-note"
              role="status"
            >
              {{ status }}
            </div>
            <article v-if="candidate" class="candidate-teaser">
              <div>
                <span class="eyebrow">{{
                  candidate.candidate.state === "ACCEPTED"
                    ? "已确认行程"
                    : "旅行草案"
                }}</span>
                <h3>
                  {{
                    candidate.candidate.state === "ACCEPTED"
                      ? "这一程，已经有了安排"
                      : "一起看看这份行程"
                  }}
                </h3>
                <p>查看每天的安排、费用及待核实事项。新候选由你确认后生效。</p>
              </div>
              <button class="secondary" @click="openDetails('行程')">
                查看行程 ↗
              </button>
            </article>
          </div>
        </div>
        <div class="chat-bottom">
          <div v-if="error" class="error banner" role="alert">
            {{ error
            }}<button v-if="retryable" :disabled="busy" @click="retryCommand">
              重试原操作
            </button>
            <button
              v-else-if="selected"
              class="quiet"
              :disabled="loading || busy"
              @click="refresh"
            >
              同步最新状态
            </button>
          </div>
          <p v-if="notice" class="receipt" role="status">{{ notice }}</p>
          <div v-if="selected" class="composer-context">
            <span :title="connection"
              ><span class="status-dot" />{{ status }}</span
            ><button
              v-if="stoppable"
              class="text-button"
              :disabled="busy || !ready"
              @click="cancel"
            >
              停止本次规划
            </button>
          </div>
          <div v-if="selected && target" class="reply-target">
            仅调整：{{
              current?.items.find((item) => item.id === target)?.title ??
              "所选安排"
            }}<button
              class="icon-button"
              aria-label="取消指定调整对象"
              @click="target = ''"
            >
              ×
            </button>
          </div>
          <ChatComposer
            v-if="selected"
            ref="composer"
            v-model="message"
            :disabled="inputDisabled"
            :answering="mode === 'ANSWER'"
            :placeholder="
              mode === 'ANSWER'
                ? '说说你的选择，或给出自己的安排…'
                : '继续聊聊，或者告诉我你想调整哪里…'
            "
            @submit="submitMessage"
          />
        </div>
      </section>
      <aside
        v-if="selected && detailsOpen"
        id="trip-details"
        class="trip-details"
        aria-label="行程与资料"
      >
        <div class="details-heading">
          <strong>这趟旅行</strong
          ><button
            class="icon-button"
            aria-label="收起行程与资料"
            @click="detailsOpen = false"
          >
            ×
          </button>
        </div>
        <nav class="details-tabs" aria-label="旅行详情分类">
          <button
            v-for="tab in ['行程', '需求', '版本', '资料']"
            :key="tab"
            :class="{ active: detailsTab === tab }"
            :aria-pressed="detailsTab === tab"
            @click="detailsTab = tab"
          >
            {{ tab }}
          </button>
        </nav>
        <div v-show="detailsTab === '需求'" class="details-content">
          <section class="panel">
            <h2>已经了解的想法</h2>
            <p class="muted">
              这里是已记录的信息，想调整时直接在对话中说明即可。
            </p>
            <p v-if="!requirements.length" class="muted">还在了解你的期待。</p>
            <dl class="requirements">
              <div v-for="requirement in requirements" :key="requirement.id">
                <dt>
                  {{ fieldLabels[requirement.field] ?? requirement.field
                  }}<small
                    >{{ requirement.kind === "HARD" ? "必须满足 · " : "偏好 · "
                    }}{{
                      requirement.source === "ASSUMPTION"
                        ? "假设，待确认"
                        : requirement.source === "AUTHORIZED_SYSTEM"
                          ? "有限授权内决定"
                          : requirement.source === "ACCEPTED_SUGGESTION"
                            ? "已采纳建议"
                            : "你提供的信息"
                    }}</small
                  >
                </dt>
                <dd>{{ requirementText(requirement) }}</dd>
              </div>
            </dl>
            <button
              class="secondary"
              @click="
                detailsOpen = false;
                composer?.focus();
              "
            >
              回到对话补充想法
            </button>
          </section>
        </div>
        <div v-show="detailsTab === '行程'" class="details-content">
          <section v-if="current" class="panel">
            <p v-if="currentNeedsValidation(view, current)" class="warning">
              需求已变化，当前行程仍待重新校验。
            </p>
            <ItineraryPanel
              :trip="current"
              title="当前生效行程"
              :locked-ids="lockedIds"
              :editable="ready && !busy && !retryable"
              @lock="lock"
            />
            <details v-if="current.items.length" class="target-picker">
              <summary>只想调整某一项安排？</summary>
              <label
                >选择要聊的安排<select v-model="target">
                  <option value="">结合完整需求判断</option>
                  <option
                    v-for="item in current.items"
                    :key="item.id"
                    :value="item.id"
                  >
                    {{ item.title }}
                  </option>
                </select></label
              ><button
                class="quiet"
                @click="
                  detailsOpen = false;
                  composer?.focus();
                "
              >
                在对话中说明调整
              </button>
            </details>
          </section>

          <section class="panel">
            <h2>推荐候选</h2>
            <template v-if="candidate">
              <p>
                依据需求版本
                {{
                  candidate.candidate.version.constraintRevision
                }}，规则校验覆盖候选完整行程。
              </p>
              <p class="tag">
                {{ candidate.candidate.state }} ·
                {{
                  candidate.candidate.state === "ACCEPTED"
                    ? "已接受，以当前生效版本为准"
                    : "候选尚未生效"
                }}
              </p>
              <p>
                规则校验：{{
                  reportLabel(candidate.candidate.validation?.conclusion)
                }}
                · 独立审核：{{
                  reportLabel(candidate.candidate.review?.conclusion)
                }}
              </p>
              <p v-if="candidate.costs">
                全组费用：{{ candidate.costs.lowerCny }}～{{
                  candidate.costs.upperCny ?? "未知"
                }}元
              </p>
              <ul class="issues">
                <li
                  v-for="(issue, index) in [
                    ...(candidate.candidate.validation?.issues ?? []),
                    ...(candidate.candidate.review?.issues ?? []),
                  ]"
                  :key="index"
                >
                  {{ issue.blocking ? "阻断" : "提示" }} · {{ issue.code }}
                  {{ issue.unknown ? "（待核实）" : "" }}
                </li>
              </ul>
              <button :disabled="!acceptable || retryable" @click="accept">
                接受这个候选
              </button>
              <button
                class="secondary"
                :disabled="
                  busy ||
                  !ready ||
                  retryable ||
                  view?.pendingInput ||
                  view?.state !== 'WAITING_ACCEPTANCE'
                "
                @click="recheck"
              >
                重新校验候选
              </button>
              <p class="muted">
                仅校验、独立审核及版本条件均有效时可接受；过期后重新检查并再次确认。
              </p>
              <ItineraryPanel
                :trip="candidate.candidate.snapshot"
                title="候选行程"
              />
              <button
                v-if="view?.currentVersionId"
                class="quiet"
                @click="inspectCandidate"
              >
                与生效版本比较
              </button>
            </template>
            <p v-else class="muted">尚无候选。等待协商和调查完成后生成。</p>
          </section>
        </div>
        <div v-show="detailsTab === '版本'" class="details-content">
          <section class="panel">
            <h2>版本历史</h2>
            <ul class="version-list">
              <li v-for="version in versions" :key="version.version.snapshotId">
                <button
                  class="quiet"
                  :title="version.version.snapshotId"
                  @click="inspect(version.version.snapshotId)"
                >
                  {{ version.version.snapshotId.slice(0, 8) }} ·
                  {{
                    version.version.snapshotId === view?.currentVersionId
                      ? "当前生效"
                      : version.accepted
                        ? "曾接受"
                        : "草案"
                  }}
                </button>
                <small>
                  {{ version.createdAt.replace("T", " ").slice(0, 19) }}
                </small>
              </li>
            </ul>
            <div class="actions">
              <button
                class="quiet"
                :disabled="!versionOffset || loading"
                @click="pageVersions(-1)"
              >
                上一页
              </button>
              <button
                class="quiet"
                :disabled="versions.length < 20 || loading"
                @click="pageVersions(1)"
              >
                下一页
              </button>
            </div>
            <template v-if="history">
              <h3>
                所选版本 {{ history.summary.version.snapshotId.slice(0, 8) }}
              </h3>
              <p class="muted">相对当前生效版本的对象差异。</p>
              <p v-if="!changes.length">没有对象差异，或尚无生效版本可比较。</p>
              <article
                v-for="change in changes"
                :key="`${change.kind}:${change.id}`"
                class="change"
              >
                <strong>{{ change.kind }} · {{ change.action }}</strong>
                <dl>
                  <template
                    v-for="field in new Set([
                      ...Object.keys(change.before),
                      ...Object.keys(change.after),
                    ])"
                    :key="field"
                  >
                    <template
                      v-if="change.before[field] !== change.after[field]"
                    >
                      <dt>{{ field }}</dt>
                      <dd>
                        <del>{{ change.before[field] ?? "无" }}</del>
                        →
                        {{ change.after[field] ?? "无" }}
                      </dd>
                    </template>
                  </template>
                </dl>
              </article>
              <details>
                <summary>调整来源与原因</summary>
                <p>
                  {{
                    history.sourceCode === "HISTORY_RESTORE"
                      ? "用户明确请求从历史行程恢复，仍沿用当前需求重新校验。"
                      : "用户输入与确认记录（原文）"
                  }}
                </p>
                <article
                  v-for="entry in history.sourceMessages ?? []"
                  :key="entry.id"
                  class="message"
                >
                  {{
                    entry.text ||
                    entry.facts
                      .map(
                        (f) => `${fieldLabels[f.field] ?? f.field}：${f.value}`,
                      )
                      .join("；") ||
                    "用户已提交明确回答"
                  }}
                </article>
                <p
                  v-if="
                    !history.sourceMessages?.length &&
                    history.sourceCode !== 'HISTORY_RESTORE'
                  "
                  class="muted"
                >
                  本版本没有可展示的原文记录，不推断调整原因。
                </p>
              </details>
              <ItineraryPanel
                :trip="history.snapshot"
                title="历史行程（只读）"
              />
              <button
                v-if="history.summary.accepted"
                class="secondary"
                :disabled="
                  !ready ||
                  busy ||
                  !view?.currentVersionId ||
                  view?.pendingInput ||
                  retryable
                "
                @click="showRestore = true"
              >
                以此历史行程生成恢复候选
              </button>
              <div v-if="showRestore" class="confirm" role="alert">
                <p>
                  恢复保留最新需求和锁定条件。系统会生成新候选，重新校验与审核，仍需要你再次接受。
                </p>
                <button :disabled="busy" @click="confirmRestore">
                  确认生成恢复候选
                </button>
                <button class="quiet" @click="showRestore = false">返回</button>
              </div>
            </template>
          </section>
        </div>
        <div v-show="detailsTab === '资料'" class="details-content">
          <section class="panel">
            <h2>资料与证据</h2>
            <p v-if="!evidence.length" class="muted">
              尚未收集到资料；未知信息不会当作已核实。
            </p>
            <details
              v-for="entry in evidence"
              :key="entry.evidence.reference.id"
            >
              <summary>
                {{ entry.evidence.fieldPath }} · {{ entry.effectiveStatus }}
              </summary>
              <p>{{ entry.evidence.targetId }}：{{ entry.evidence.value }}</p>
              <p class="muted">
                {{ entry.reason }} · 获取：{{ entry.evidence.retrievedAt }} ·
                有效至：{{ entry.evidence.expiresAt }}
              </p>
              <a
                v-if="sourceLink(entry.evidence.sourceUrl)"
                :href="sourceLink(entry.evidence.sourceUrl)"
                target="_blank"
                rel="noopener noreferrer"
              >
                查看来源
              </a>
            </details>
          </section>
        </div>
      </aside>
    </main>
    <dialog
      v-if="preferencesOpen && user"
      ref="preferenceDialog"
      class="preferences-dialog"
      aria-labelledby="preferences-heading"
      @close="preferencesOpen = false"
    >
      <div class="details-heading">
        <h2 id="preferences-heading">我的旅行偏好</h2>
        <button
          class="icon-button"
          autofocus
          aria-label="关闭旅行偏好"
          @click="preferenceDialog?.close()"
        >
          ×
        </button>
      </div>
      <PreferencePanel
        :key="user.id"
        open
        :requirements="requirements"
        @changed="refresh"
      />
    </dialog>
  </div>
</template>
