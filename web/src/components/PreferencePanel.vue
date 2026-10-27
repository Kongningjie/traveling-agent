<!-- 用户私有长期偏好；建议先待确认，删除与本次独立旅行约束分开；账号切换销毁全部文本。 -->
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import { Api, ApiError } from "../workspace/client";
import type { Requirement } from "../workspace/types";
/** 当前任务的已确认要求仅供用户选择提出建议，不会自动长期保存。 */
defineProps<{ requirements: Requirement[] }>();
const emit = defineEmits<{ changed: [] }>();
/** 仅后端返回的有效条目，墓碑不暴露；范围为空表示通用。 */
interface Entry {
  id: string;
  category: string;
  value: string;
  city: string;
  source: "USER" | "SUGGESTION";
  state: "CONFIRMED" | "PENDING";
  updatedAt: string;
}
/** 与条目一致的用户级版本，写入必须提交该版本。 */
interface View {
  version: { userId: string; revision: number };
  entries: Entry[];
}
/** 未知网络结果保留完全相同的命令，用户显式重试不新建请求键。 */
interface Command {
  method: "POST" | "PATCH" | "DELETE";
  path: string;
  body: unknown;
}
const api = new Api(),
  data = ref<View>(),
  error = ref(""),
  busy = ref(false),
  category = ref("pace"),
  value = ref(""),
  city = ref(""),
  editing = ref(""),
  deleting = ref(""),
  pending = ref<Command>();
const labels: Record<string, string> = {
  pace: "旅行节奏",
  diet: "饮食",
  interests: "兴趣",
  transport: "交通倾向",
};
let active = true,
  ticket = 0;
/** 当前组件销毁后不能把旧账号响应写回；每次手动刷新只采纳最后请求。 */
async function load(): Promise<void> {
  const current = ++ticket;
  try {
    const result = await api.get<View>("/api/v1/preferences");
    if (active && current === ticket) {
      data.value = result;
      deleting.value = "";
    }
  } catch (failure) {
    if (active && current === ticket) handle(failure);
  }
}
/**
 * @param failure 固定HTTP失败；身份失效即清私有数据并请父工作台重新核对会话。
 */
function handle(failure: unknown): void {
  error.value = failure instanceof ApiError ? failure.message : "偏好操作失败，请刷新核对。";
  if (failure instanceof ApiError && [401, 403].includes(failure.status)) {
    data.value = undefined;
    value.value = "";
    city.value = "";
    pending.value = undefined;
    emit("changed");
  }
}
/**
 * @param command 当前确认的命令；同键重试只改变观察结果，不重复保存或恢复已删除偏好。
 */
async function execute(command: Command): Promise<void> {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  pending.value = command;
  ++ticket;
  try {
    const result = await api.write<View>(command.method, command.path, command.body);
    if (!active) return;
    data.value = result;
    pending.value = undefined;
    editing.value = "";
    deleting.value = "";
    value.value = "";
    city.value = "";
    emit("changed");
  } catch (failure) {
    if (!active) return;
    handle(failure);
    if (failure instanceof ApiError && failure.status !== 0) {
      pending.value = undefined;
      await load();
    }
  } finally {
    if (active) busy.value = false;
  }
}
/**
 * @param suggestion 仅提出建议；不允许默认选中确认，直接保存需用户主动点击对应按钮。
 */
function save(suggestion = false): void {
  if (!data.value || !value.value.trim() || pending.value) return;
  const common = {
    requestId: crypto.randomUUID(),
    revision: data.value.version.revision,
    category: category.value,
    value: value.value.trim(),
    city: city.value.trim(),
  };
  void execute(
    editing.value
      ? {
          method: "PATCH",
          path: `/api/v1/preferences/${editing.value}`,
          body: common,
        }
      : {
          method: "POST",
          path: "/api/v1/preferences",
          body: { ...common, id: crypto.randomUUID(), suggestion },
        },
  );
}
/**
 * @param entry 当前条目；只复制到显式编辑框，尚未保存。
 */
function edit(entry: Entry): void {
  editing.value = entry.id;
  category.value = entry.category;
  value.value = entry.value;
  city.value = entry.city;
}
/**
 * @param entry 被确认或删除的条目
 * @param action 独立确认建议或确认删除；本次旅行约束继续有效。
 */
function intent(entry: Entry, action: "confirm" | "delete"): void {
  if (!data.value || pending.value) return;
  void execute({
    method: action === "confirm" ? "POST" : "DELETE",
    path: `/api/v1/preferences/${entry.id}${action === "confirm" ? "/confirm" : ""}`,
    body: {
      requestId: crypto.randomUUID(),
      revision: data.value.version.revision,
    },
  });
}
/**
 * @param requirement 本次确认的软要求，用户先提出建议，再另行确认成为长期偏好。
 */
function suggest(requirement: Requirement): void {
  editing.value = "";
  category.value = requirement.field;
  value.value = requirement.value;
  city.value = "";
  save(true);
}
onMounted(load);
onBeforeUnmount(() => {
  active = false;
  ++ticket;
  data.value = undefined;
  pending.value = undefined;
  value.value = "";
  city.value = "";
});
</script>

<template>
  <details class="panel preference-panel">
    <summary>长期偏好管理</summary>
    <p class="muted">
      仅保存你明确确认的长期软偏好。本次旅行要求优先；删除长期偏好不会删除已独立确认的旅行要求，也不会改写已接受行程。
    </p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <button class="quiet" :disabled="busy || !!pending" @click="load">刷新偏好</button>
    <button v-if="pending" :disabled="busy" @click="execute(pending)">重试原操作</button>
    <div v-if="data">
      <p v-if="!data.entries.length" class="muted">尚无长期偏好。</p>
      <article v-for="entry in data.entries" :key="entry.id" class="preference-entry">
        <strong>{{ labels[entry.category] }} · {{ entry.city || "通用" }}</strong>
        <p>{{ entry.value }}</p>
        <small
          >{{ entry.state === "PENDING" ? "建议，待你确认（不参与规划）" : "已确认" }} ·
          {{ entry.source === "USER" ? "用户主动保存" : "来自建议" }} ·
          {{ new Date(entry.updatedAt).toLocaleString() }}</small
        >
        <div class="row">
          <button
            v-if="entry.state === 'PENDING'"
            :disabled="busy || !!pending"
            @click="intent(entry, 'confirm')"
          >
            确认长期保存
          </button>
          <button v-else class="quiet" :disabled="busy || !!pending" @click="edit(entry)">
            修改
          </button>
          <button class="quiet" :disabled="busy || !!pending" @click="deleting = entry.id">
            {{ entry.state === "PENDING" ? "拒绝建议" : "删除" }}
          </button>
          <button
            v-if="deleting === entry.id"
            :disabled="busy || !!pending"
            @click="intent(entry, 'delete')"
          >
            确认移除长期偏好
          </button>
        </div>
      </article>
      <form class="edit-form" @submit.prevent="save(false)">
        <label
          >类别<select v-model="category">
            <option v-for="(label, key) in labels" :key="key" :value="key">
              {{ label }}
            </option>
          </select></label
        >
        <label
          >内容<textarea v-model="value" maxlength="500" required :disabled="busy || !!pending" />
        </label>
        <label
          >适用城市（留空为通用）<input v-model="city" maxlength="80" :disabled="busy || !!pending"
        /></label>
        <button :disabled="busy || !!pending || !value.trim()">
          {{ editing ? "明确保存修改" : "明确长期保存" }}
        </button>
        <button
          v-if="editing"
          type="button"
          class="quiet"
          :disabled="busy || !!pending"
          @click="
            editing = '';
            value = '';
            city = '';
          "
        >
          取消编辑
        </button>
      </form>
      <template v-for="requirement in requirements" :key="requirement.id">
        <button
          v-if="requirement.kind === 'SOFT' && requirement.field in labels"
          class="quiet"
          :disabled="busy || !!pending"
          @click="suggest(requirement)"
        >
          将本次「{{ labels[requirement.field] }}」提出为长期偏好建议
        </button>
      </template>
    </div>
  </details>
</template>

<style scoped>
.preference-entry {
  padding: 1rem 0;
  border-bottom: 1px solid var(--line, #ddd);
  overflow-wrap: anywhere;
}
.preference-entry .row {
  flex-wrap: wrap;
  margin-top: 0.6rem;
}
summary {
  cursor: pointer;
  font-weight: 700;
}
</style>
