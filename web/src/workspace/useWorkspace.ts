import { computed, onUnmounted, ref } from "vue";
import { cursorSequence, decodeEvent, applyEvent } from "../events/runEvents";
import { Api, ApiError, decodeUser } from "./client";
import { canAccept, canAnswer } from "./guards";
import { conversationInput } from "./conversation";
import type {
  CandidateView,
  Card,
  Change,
  Evidence,
  Fact,
  Message,
  Task,
  Trip,
  User,
  VersionDetail,
  VersionSummary,
  View,
} from "./types";

/** 私有旅行工作台状态；取消读取与断线只影响UI，写入依赖服务端幂等和版本门禁。 */
export function useWorkspace() {
  const api = new Api();
  const user = ref<User | null>(null),
    tasks = ref<Task[]>([]),
    selected = ref(""),
    view = ref<View | null>(null);
  const messages = ref<Message[]>([]),
    card = ref<Card | null>(null),
    candidate = ref<CandidateView | null>(null),
    versions = ref<VersionSummary[]>([]),
    evidence = ref<Evidence[]>([]);
  const history = ref<VersionDetail | null>(null),
    changes = ref<Change[]>([]),
    busy = ref(false),
    loading = ref(false),
    ready = ref(false),
    error = ref(""),
    notice = ref(""),
    connection = ref("未连接");
  const taskOffset = ref(0),
    versionOffset = ref(0);
  let epoch = 0,
    abort: AbortController | null = null,
    stream: EventSource | null = null,
    timer: ReturnType<typeof setTimeout> | null = null,
    polling: ReturnType<typeof setInterval> | null = null;
  let rerun = false,
    retry: (() => Promise<unknown>) | null = null;
  const retryable = ref(false);
  const acceptable = computed(
    () => !busy.value && canAccept(view.value, candidate.value, ready.value),
  );
  const answerable = computed(
    () => !busy.value && canAnswer(view.value, card.value, ready.value),
  );
  const current = computed(
    () => view.value?.itinerary as unknown as Trip | null,
  );
  /** 释放连接及读取，递增代次使旧账号／旧任务的迟到响应无法写回。 */
  function disconnect(): void {
    epoch++;
    abort?.abort();
    abort = null;
    stream?.close();
    stream = null;
    if (timer) clearTimeout(timer);
    timer = null;
    if (polling) clearInterval(polling);
    polling = null;
    rerun = false;
    loading.value = false;
    ready.value = false;
  }
  /** 清除私有界面，绝不把上个账号内容保留到新登录。 */
  function clearTask(): void {
    disconnect();
    selected.value = "";
    view.value = null;
    messages.value = [];
    card.value = null;
    candidate.value = null;
    versions.value = [];
    evidence.value = [];
    history.value = null;
    changes.value = [];
    retry = null;
    retryable.value = false;
    connection.value = "未连接";
    notice.value = "";
    error.value = "";
  }
  /** 用户主动开启空白对话，不取消原任务；清除选中ID以防刷新又跳回旧对话，未知命令不能丢弃。 */
  function newConversation(): void {
    if (busy.value || retryable.value) return;
    if (user.value) sessionStorage.removeItem(`travel.task.${user.value.id}`);
    clearTask();
  }
  /** 明确删除已成功后清除旧页面和未知重试，防止迟到响应恢复已删除内容。 */
  function forgetDeleted(): void {if (user.value) sessionStorage.removeItem(`travel.task.${user.value.id}`); clearTask(); busy.value = false;}
  /** @param failure 固定异常 @param stamp 请求所属页面代次；过时异常也不能退出新账号 */
  function failed(failure: unknown, stamp: number): void {
    if (stamp !== epoch) return;
    const explanation =
      failure instanceof ApiError ? failure.message : "暂时无法完成，请重试。";
    if (failure instanceof ApiError && [401, 403].includes(failure.status)) {
      clearTask();
      tasks.value = [];
      user.value = null;
    } else if (failure instanceof ApiError && failure.status === 404)
      clearTask();
    error.value = explanation;
  }
  /** 读取本人任务；结果只应用于发起读取的身份及代次。 */
  async function listTasks(): Promise<void> {
    const stamp = epoch;
    try {
      const result = await api.get<Task[]>(
        `/api/v1/tasks?offset=${taskOffset.value}&limit=20`,
      );
      if (stamp === epoch) tasks.value = result;
    } catch (failure) {
      failed(failure, stamp);
    }
  }
  /** 组件挂载恢复Cookie会话和上次选中的任务ID；不存储用户正文、Cookie或令牌。 */
  async function initialize(): Promise<void> {
    const stamp = epoch;
    try {
      const me = decodeUser(await api.get<unknown>("/api/v1/auth/me"));
      if (stamp !== epoch) return;
      user.value = me;
      await listTasks();
      const saved = sessionStorage.getItem(`travel.task.${me.id}`);
      if (saved && user.value?.id === me.id) await selectTask(saved);
    } catch (failure) {
      if (!(failure instanceof ApiError && failure.status === 401))
        failed(failure, stamp);
    }
  }
  /** @param username 用户名 @param password 仅本次提交使用 @param register 是否先注册；凭证不写存储、不保留重试闭包 */
  async function login(
    username: string,
    password: string,
    register: boolean,
  ): Promise<void> {
    if (busy.value) return;
    clearTask();
    tasks.value = [];
    busy.value = true;
    error.value = "";
    const stamp = epoch;
    try {
      if (register)
        await api.post("/api/v1/auth/register", { username, password });
      const me = decodeUser(
        await api.post<unknown>("/api/v1/auth/login", {
          username,
          password,
        }),
      );
      if (stamp !== epoch) return;
      user.value = me;
      taskOffset.value = 0;
      await listTasks();
    } catch (failure) {
      failed(failure, stamp);
    } finally {
      busy.value = false;
    }
  }
  /** 注销只在服务端成功后清除身份；失败时展示安全错误，用户可重试。 */
  async function logout(): Promise<void> {
    if (busy.value) return;
    busy.value = true;
    const stamp = epoch;
    try {
      await api.post("/api/v1/auth/logout", {});
      clearTask();
      user.value = null;
      tasks.value = [];
    } catch (failure) {
      failed(failure, stamp);
    } finally {
      busy.value = false;
    }
  }
  /** @param task 本人任务ID；切换立刻清空旧内容，不触发后端取消 */
  async function selectTask(task: string): Promise<void> {
    if (busy.value) return;
    clearTask();
    selected.value = task;
    versionOffset.value = 0;
    if (user.value)
      sessionStorage.setItem(`travel.task.${user.value.id}`, task);
    await refresh();
    const stamp = epoch;
    polling = setInterval(() => {
      if (stamp === epoch) void refresh();
    }, 10000);
  }
  /** 读取前后水位一致才展示可操作状态；防止不同接口读取间的新需求与旧候选混搭。 */
  async function refresh(): Promise<void> {
    if (!selected.value) return;
    if (loading.value) {
      rerun = true;
      return;
    }
    const task = selected.value,
      stamp = epoch;
    loading.value = true;
    ready.value = false;
    rerun = false;
    abort = new AbortController();
    const signal = abort.signal;
    try {
      const before = await api.get<View>(`/api/v1/tasks/${task}/view`, signal);
      const [m, d, c, v, e] = await Promise.all([
        api.get<Message[]>(`/api/v1/tasks/${task}/messages`, signal),
        api.get<Card | null>(`/api/v1/tasks/${task}/decision`, signal),
        api.get<CandidateView | null>(
          `/api/v1/tasks/${task}/candidate`,
          signal,
        ),
        api.get<VersionSummary[]>(
          `/api/v1/tasks/${task}/versions?offset=${versionOffset.value}&limit=20`,
          signal,
        ),
        api.get<Evidence[]>(`/api/v1/tasks/${task}/evidence`, signal),
      ]);
      const after = await api.get<View>(`/api/v1/tasks/${task}/view`, signal);
      if (stamp !== epoch) return;
      if (before.cursor !== after.cursor) {
        rerun = true;
        return;
      }
      if (
        view.value &&
        cursorSequence(task, after.cursor) <
          cursorSequence(task, view.value.cursor)
      ) {
        rerun = true;
        return;
      }
      if (view.value?.currentVersionId !== after.currentVersionId) {
        history.value = null;
        changes.value = [];
      }
      view.value = after;
      messages.value = m;
      card.value = d;
      candidate.value = c;
      versions.value = v;
      evidence.value = e;
      ready.value = true;
      if (!stream) connect(task, stamp, after.cursor);
    } catch (failure) {
      failed(failure, stamp);
    } finally {
      if (stamp === epoch) {
        loading.value = false;
        if (rerun && !timer)
          timer = setTimeout(() => {
            timer = null;
            void refresh();
          }, 100);
      }
    }
  }
  /** @param task 当前任务 @param stamp 页面代次 @param initial 一致快照水位；只推进实际应用的事件，断线后补读 */
  function connect(task: string, stamp: number, initial: string): void {
    let cursor = initial;
    const channel = new EventSource(
      `/api/v1/tasks/${task}/events/stream?after=${encodeURIComponent(cursor)}`,
    );
    stream = channel;
    channel.onopen = () => {
      if (stamp === epoch) connection.value = "实时更新已连接";
    };
    channel.addEventListener("run-event", (message: MessageEvent<string>) => {
      if (stamp !== epoch || !view.value) return;
      try {
        const event = decodeEvent(task, message.data, message.lastEventId);
        const result = applyEvent(view.value, cursor, event);
        cursor = result.cursor;
        if (result.applied) {
          ready.value = false;
          void refresh();
        }
      } catch {
        channel.onerror?.(new Event("error"));
      }
    });
    channel.onerror = () => {
      channel.close();
      if (stamp !== epoch) return;
      stream = null;
      connection.value = "连接中断，正在补读";
      ready.value = false;
      if (!timer)
        timer = setTimeout(() => {
          timer = null;
          void refresh();
        }, 1000);
    };
  }
  /** @param command 已冻结幂等命令；未知网络结果仅供用户显式原样重试，不自动重发 */
  async function execute(command: () => Promise<unknown>): Promise<void> {
    if (busy.value) return;
    const stamp = epoch;
    let created: string | null = null;
    busy.value = true;
    ready.value = false;
    error.value = "";
    retry = null;
    retryable.value = false;
    try {
      const result = await command();
      if (stamp !== epoch) return;
      if (
        result &&
        typeof result === "object" &&
        "status" in result &&
        result.status === "REVALIDATION_REQUIRED"
      )
        notice.value = "已请求重新校验；完成后需要你再次接受。";
      else notice.value = "已保存你的操作。";
      await refresh();
      await listTasks();
      if (
        result &&
        typeof result === "object" &&
        "taskId" in result &&
        typeof result.taskId === "string"
      )
        created = result.taskId;
    } catch (failure) {
      if (
        stamp === epoch &&
        failure instanceof ApiError &&
        failure.status === 0
      ) {
        retry = command;
        retryable.value = true;
      }
      failed(failure, stamp);
    } finally {
      busy.value = false;
    }
    if (created && stamp === epoch) await selectTask(created);
  }
  /** 用户显式重试同一闭包，保留原requestId、前置版本及业务输入。 */
  async function retryCommand(): Promise<void> {
    if (retry) await execute(retry);
  }
  /** @param text 用户完整旅行想法；不默认任何范围、预算或已订状态，缺项由后端协商。 */
  async function createTask(text: string): Promise<void> {
    const body = { requestId: crypto.randomUUID(), ...conversationInput(text) };
    await execute(() => api.post<{ taskId: string }>("/api/v1/tasks", body));
  }
  /** @param text 自由修改 @param facts 明确字段编辑；保留用户尚未明确修改的要求 */
  async function send(text: string, facts: Fact[] = []): Promise<void> {
    const task = selected.value;
    const body = { inputId: crypto.randomUUID(), text, facts };
    await execute(() => api.post(`/api/v1/tasks/${task}/messages`, body));
  }
  /** @param action 明确动作 @param selectedOptions 本次明确选择 @param text 自由回答 @param authorization 显式勾选的有限范围 */
  async function answer(
    action: string,
    selectedOptions: Record<string, string>,
    text: string,
    authorization: string[],
  ): Promise<void> {
    if (!answerable.value || !card.value) return;
    const task = selected.value,
      d = card.value.decision;
    const body = {
      inputId: crypto.randomUUID(),
      response: {
        decisionId: d.id,
        revision: d.revision,
        constraints: d.constraintRevision,
        action,
        selected: { ...selectedOptions },
        text,
        authorization: [...authorization],
      },
    };
    await execute(() => api.post(`/api/v1/tasks/${task}/answers`, body));
  }
  /** 接受只针对已显示的精确候选，过期返回重校验，绝不自动再次接受。 */
  async function accept(): Promise<void> {
    if (!acceptable.value || !view.value || !candidate.value) return;
    const task = selected.value,
      v = candidate.value.candidate.version;
    const body = {
      requestId: crypto.randomUUID(),
      candidateId: candidate.value.candidate.candidateId,
      expectedCurrentVersionId: v.baseVersionId,
      constraintRevision: v.constraintRevision,
      generation: view.value.generation,
      snapshotHash: v.payloadHash,
    };
    await execute(() =>
      api.post<{ status: string }>(`/api/v1/tasks/${task}/accept`, body),
    );
  }
  /** 用户只要求重新校验时使用独立入口；即使报告恢复新鲜也不会被解释为接受。 */
  async function recheck(): Promise<void> {
    if (
      busy.value ||
      !ready.value ||
      !view.value ||
      view.value.pendingInput ||
      view.value.state !== "WAITING_ACCEPTANCE" ||
      !candidate.value
    )
      return;
    const task = selected.value,
      value = candidate.value.candidate;
    const body = {
      requestId: crypto.randomUUID(),
      candidateId: value.candidateId,
      expectedCurrentVersionId: value.version.baseVersionId,
      constraintRevision: value.version.constraintRevision,
      generation: view.value.generation,
      snapshotHash: value.version.payloadHash,
    };
    await execute(() =>
      api.post(`/api/v1/tasks/${task}/candidate/revalidate`, body),
    );
  }
  // 独立读取序号防止同任务内快速选择历史时的响应乱序。
  let historyTicket = 0;
  /** @param id 历史快照ID，读取后比较当前生效版本；不触发恢复 */
  async function inspect(id: string): Promise<void> {
    const stamp = epoch,
      ticket = ++historyTicket,
      task = selected.value,
      reference = view.value?.currentVersionId;
    history.value = null;
    changes.value = [];
    try {
      const detail = await api.get<VersionDetail>(
        `/api/v1/tasks/${task}/versions/${id}`,
      );
      const diff = reference
        ? await api.get<Change[]>(
            `/api/v1/tasks/${task}/version-diff?from=${reference}&to=${id}`,
          )
        : [];
      if (
        stamp === epoch &&
        ticket === historyTicket &&
        view.value?.currentVersionId === reference
      ) {
        history.value = detail;
        changes.value = diff;
      }
    } catch (failure) {
      failed(failure, stamp);
    }
  }
  /** @param snapshot 历史目标；CAS预期从已显示一致视图捕获，恢复仍需校验、审核、再次接受 */
  async function restore(snapshot: string): Promise<void> {
    if (
      !ready.value ||
      !view.value?.currentVersionId ||
      view.value.pendingInput
    )
      return;
    const task = selected.value;
    const body = {
      requestId: crypto.randomUUID(),
      expectedCurrent: view.value.currentVersionId,
      revision: view.value.constraintRevision,
      generation: view.value.generation,
    };
    await execute(() =>
      api.post(`/api/v1/tasks/${task}/versions/${snapshot}/restore`, body),
    );
  }
  /** @param id 业务对象 @param locked 明确要保留或解除；提交完整锁定集合，不由推荐自动更改 */
  async function lock(id: string, locked: boolean): Promise<void> {
    const reqs = (view.value?.requirements.requirements ?? []) as {
      field: string;
      value: string;
    }[];
    const raw = reqs.find((r) => r.field === "lockedIds")?.value;
    const ids = new Set(raw && raw !== "NONE" ? raw.split(",") : []);
    if (locked) ids.add(id);
    else ids.delete(id);
    await send("", [
      { field: "lockedIds", value: [...ids].join(",") || "NONE", quote: null },
    ]);
  }
  /** 显式取消当前运行；关闭页面或断线均不执行这里。 */
  async function cancel(): Promise<void> {
    if (!view.value?.runId) return;
    const task = selected.value,
      run = view.value.runId;
    await execute(() =>
      api.post(`/api/v1/tasks/${task}/runs/${run}/cancel`, {}),
    );
  }
  /** @param direction 上一页或下一页，不依赖本地数据猜测总页数 */
  async function pageTasks(direction: number): Promise<void> {
    taskOffset.value = Math.max(0, taskOffset.value + direction * 20);
    await listTasks();
  }
  /** @param direction 历史分页，切换后重新读取一致视图 */
  async function pageVersions(direction: number): Promise<void> {
    versionOffset.value = Math.max(0, versionOffset.value + direction * 20);
    await refresh();
  }
  onUnmounted(disconnect);
  return {
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
    forgetDeleted,
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
  };
}
