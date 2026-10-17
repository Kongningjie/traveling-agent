<!-- 私有旅行工作台：会话、协商、版本、证据和确认均以服务端状态为权威。 -->
<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import PreferencePanel from "./components/PreferencePanel.vue";
import CreateTrip from "./components/CreateTrip.vue";
import DecisionCard from "./components/DecisionCard.vue";
import ItineraryPanel from "./components/ItineraryPanel.vue";
import { sourceLink, currentNeedsValidation } from "./workspace/guards";
import { useWorkspace } from "./workspace/useWorkspace";
import type { Requirement } from "./workspace/types";
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
  editField = ref("interests"),
  editValue = ref(""),
  target = ref(""),
  showRestore = ref(false);
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
  if (!message.value.trim()) return;
  const text = message.value,
    task = selected.value;
  const facts = target.value
    ? [{ field: "changeTargets", value: target.value, quote: null }]
    : [];
  await send(text, facts);
  if (!error.value && selected.value === task && message.value === text)
    message.value = "";
}
/** 结构化编辑复用可靠修改用例，不在前端直接覆盖需求或快照。 */
async function editRequirement(): Promise<void> {
  if (!editValue.value.trim()) return;
  const value = editValue.value,
    field = editField.value,
    task = selected.value;
  await send("", [{ field, value, quote: null }]);
  if (!error.value && selected.value === task && editValue.value === value)
    editValue.value = "";
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
  () => {
    message.value = "";
    target.value = "";
    editValue.value = "";
    password.value = "";
  },
);
// 当前任务、历史选择或服务端水位变化后重新确认，不能把旧确认意图用于新状态。
watch(
  () => [
    selected.value,
    history.value?.summary.version.snapshotId,
    view.value?.cursor,
  ],
  () => {
    showRestore.value = false;
  },
);
onMounted(initialize);
</script>
<template>
  <div class="app-shell">
    <header class="topbar">
      <a class="brand" href="/">
        行旅
        <span>旅行助手</span>
      </a>
      <span class="muted">一起把每一程安排好</span>
      <div v-if="user" class="actions">
        <span>{{ user.username }}</span>
        <button class="quiet" :disabled="busy" @click="logout">退出登录</button>
      </div>
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
    <main v-else class="workbench">
      <aside class="sidebar panel">
        <div class="row">
          <h2>我的旅行</h2>
          <button class="quiet" :disabled="busy" @click="pageTasks(0)">
            刷新
          </button>
        </div>
        <CreateTrip :disabled="busy || retryable" @create="createTask" />
        <nav aria-label="旅行任务">
          <button
            v-for="task in tasks"
            :key="task.task.scope.taskId"
            class="task-button"
            :class="{ active: selected === task.task.scope.taskId }"
            :disabled="busy"
            @click="selectTask(task.task.scope.taskId)"
          >
            <strong>{{ task.title }}</strong>
            <small>
              需求版本 {{ task.task.constraintRevision }} ·
              {{ task.task.currentVersionId ? "已有生效行程" : "待规划" }}
            </small>
          </button>
          <p v-if="!tasks.length" class="muted">
            暂无旅行，从创建一份计划开始。
          </p>
        </nav>
        <div class="actions">
          <button
            class="quiet"
            :disabled="busy || !taskOffset"
            @click="pageTasks(-1)"
          >
            上一页
          </button>
          <button
            class="quiet"
            :disabled="busy || tasks.length < 20"
            @click="pageTasks(1)"
          >
            下一页
          </button>
        </div>
      </aside>
      <section class="workspace-main">
        <PreferencePanel
          :key="user.id"
          :requirements="requirements"
          @changed="refresh"
        />
        <div v-if="error" class="error banner" role="alert">
          {{ error }}
          <button v-if="retryable" :disabled="busy" @click="retryCommand">
            重试原操作
          </button>
        </div>
        <p v-if="notice" class="banner" role="status">{{ notice }}</p>
        <template v-if="selected">
          <section class="panel">
            <div class="row">
              <div>
                <p class="eyebrow">{{ connection }}</p>
                <h1 class="task-title">
                  {{
                    tasks.find((t) => t.task.scope.taskId === selected)
                      ?.title ?? "旅行工作台"
                  }}
                </h1>
              </div>
              <button
                class="secondary"
                :disabled="loading || busy"
                @click="refresh"
              >
                {{ loading ? "同步中…" : "刷新" }}
              </button>
            </div>
            <p role="status">
              {{ status }} · 需求 {{ view?.constraintRevision ?? "—" }} ·
              执行代次 {{ view?.generation ?? "—" }}
            </p>
            <p v-if="view?.pendingInput" class="warning">
              新修改正在处理，旧候选暂不可接受。
            </p>
            <button
              v-if="
                view?.runId &&
                [
                  'QUEUED',
                  'RUNNING',
                  'WAITING_USER',
                  'WAITING_ACCEPTANCE',
                  'PAUSED_BUDGET',
                  'PAUSED_QUOTA',
                ].includes(view.state ?? '')
              "
              class="quiet"
              :disabled="busy || !ready"
              @click="cancel"
            >
              停止当前执行
            </button>
          </section>
          <section class="panel">
            <h2>已确认的旅行需求</h2>
            <p v-if="!requirements.length" class="muted">需求尚待共同确认。</p>
            <dl class="requirements">
              <div v-for="requirement in requirements" :key="requirement.id">
                <dt>
                  {{ fieldLabels[requirement.field] ?? requirement.field }}
                  <small>
                    {{ requirement.kind === "HARD" ? "硬约束" : "偏好" }} ·
                    {{
                      requirement.source === "ASSUMPTION"
                        ? "假设，待核实"
                        : requirement.source === "AUTHORIZED_SYSTEM"
                          ? "已授权"
                          : requirement.source === "ACCEPTED_SUGGESTION"
                            ? "已采纳建议"
                            : "用户提供"
                    }}
                  </small>
                </dt>
                <dd>{{ requirement.value }}</dd>
              </div>
            </dl>
            <details>
              <summary>修改明确字段</summary>
              <form class="edit-form" @submit.prevent="editRequirement">
                <label>
                  字段
                  <select v-model="editField">
                    <option
                      v-for="field in [
                        'cities',
                        'startDate',
                        'endDate',
                        'travelers',
                        'budgetRule',
                        'budgetCny',
                        'budgetScope',
                        'pace',
                        'diet',
                        'interests',
                        'hardRequirements',
                        'fixedStatus',
                        'fixedArrangements',
                      ]"
                      :key="field"
                    >
                      {{ field }}
                    </option>
                  </select>
                </label>
                <label>
                  新的明确内容
                  <textarea v-model="editValue" required maxlength="500" />
                </label>
                <button :disabled="busy || !ready || retryable">
                  保存修改并重新规划
                </button>
              </form>
              <p class="muted">
                硬限制仅补充，不自动放宽。固定安排格式：类型|完整带时区起时|完整带时区止时。
              </p>
            </details>
          </section>
          <DecisionCard
            v-if="card"
            :key="`${card.decision.id}:${card.decision.revision}`"
            :card="card"
            :enabled="answerable && !retryable"
            @answer="answer"
          />
          <section class="panel">
            <h2>一起完善这趟旅行</h2>
            <div class="conversation" aria-live="polite">
              <article v-for="item in messages" :key="item.id" class="message">
                <p>
                  {{
                    item.text ||
                    item.facts
                      .map((f) => `${f.field}：${f.value}`)
                      .join("；") ||
                    "已提交回答"
                  }}
                </p>
                <small>{{ item.kind }} · {{ item.state }}</small>
              </article>
              <p v-if="!messages.length" class="muted">
                你的想法与确认记录会显示在这里，刷新后仍可继续。
              </p>
            </div>
            <form @submit.prevent="submitMessage">
              <label>
                我的想法
                <textarea
                  v-model="message"
                  rows="3"
                  maxlength="4000"
                  placeholder="例如：第二天下午留更多休息时间"
                  required
                />
              </label>
              <label>
                仅调整某项安排（可选）
                <select v-model="target">
                  <option value="">由系统结合完整需求判断</option>
                  <option
                    v-for="item in current?.items ?? []"
                    :key="item.id"
                    :value="item.id"
                  >
                    {{ item.title }}
                  </option>
                </select>
              </label>
              <button :disabled="busy || retryable || !message.trim()">
                发送修改／补充并继续
              </button>
            </form>
          </section>
          <section v-if="current" class="panel">
            <p v-if="currentNeedsValidation(view, current)" class="warning">
              需求已变化，当前行程仍绑定需求版本
              {{
                current.constraintRevision ?? "未知"
              }}，需重新校验，不能视为已满足最新要求。
            </p>
            <ItineraryPanel
              :trip="current"
              title="当前生效行程"
              :locked-ids="lockedIds"
              :editable="ready && !busy && !retryable"
              @lock="lock"
            />
          </section>
        </template>
        <section v-else class="panel empty">
          <h1>下一趟旅行，想去哪里？</h1>
          <p>
            创建新旅行，或从左侧继续已有计划。每个新版本都由你决定是否接受。
          </p>
        </section>
      </section>
      <aside v-if="selected" class="versions-column">
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
              @click="inspect(candidate.candidate.version.snapshotId)"
            >
              与生效版本比较
            </button>
          </template>
          <p v-else class="muted">尚无候选。等待协商和调查完成后生成。</p>
        </section>
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
                  <template v-if="change.before[field] !== change.after[field]">
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
            <ItineraryPanel :trip="history.snapshot" title="历史行程（只读）" />
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
        <section class="panel">
          <h2>资料与证据</h2>
          <p v-if="!evidence.length" class="muted">
            尚未收集到资料；未知信息不会当作已核实。
          </p>
          <details v-for="entry in evidence" :key="entry.evidence.reference.id">
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
      </aside>
    </main>
    <footer>行程建议需结合实际核实；新候选由你确认后生效。</footer>
  </div>
</template>
