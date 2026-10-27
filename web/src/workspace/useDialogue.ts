import { onUnmounted, ref, watch, type Ref } from "vue";
import { Api, ApiError } from "./client";
import type { User } from "./types";

import { decodeChat, decodeChatView } from "./dialogue";
import type { Chat, ChatView } from "./dialogue";

/**
 * @param user 当前身份
 * @param selectTask 关联任务后沿用工作台读取，切换不取消执行。
 */
export function useDialogue(user: Ref<User | null>, selectTask: (id: string) => Promise<void>) {
  const api = new Api();
  const chats = ref<Chat[]>([]),
    current = ref<ChatView | null>(null),
    selected = ref("");
  const busy = ref(false),
    error = ref(""),
    retryable = ref(false),
    offset = ref(0);
  let ticket = 0,
    timer: ReturnType<typeof setInterval> | null = null;
  let retry: (() => Promise<ChatView>) | null = null;
  /** 清除账号私有状态与轮询，迟到响应不能污染新聊天。 */
  function clear(): void {
    ticket++;
    if (timer) clearInterval(timer);
    timer = null;
    current.value = null;
    selected.value = "";
    error.value = "";
    retry = null;
    retryable.value = false;
    busy.value = false;
  }
  /**
   * @param stamp 请求代次
   * @param view 服务端视图，旧聊天回复不得切换新页面。
   */
  async function apply(stamp: number, view: ChatView): Promise<void> {
    if (stamp !== ticket) return;
    const oldTask = current.value?.chat.taskId;
    current.value = view;
    selected.value = view.chat.id;
    if (user.value) sessionStorage.setItem(`travel.chat.${user.value.id}`, view.chat.id);
    if (view.chat.taskId && oldTask !== view.chat.taskId) await selectTask(view.chat.taskId);
  }
  /**
   * @param failure 请求失败，仅显示固定友好消息，不回显服务端原文。
   */
  function failed(failure: unknown): void {
    error.value = failure instanceof ApiError ? failure.message : "聊天暂时无法同步，请重试。";
    if (failure instanceof ApiError && [401, 403].includes(failure.status)) {
      clear();
      chats.value = [];
      user.value = null;
    }
  }
  /**
   * @param stamp 请求代次
   * @returns 私有列表，分页不会改变当前选中聊天。
   */
  async function list(stamp = ticket): Promise<void> {
    try {
      const raw = await api.get<unknown>(`/api/v1/chats?offset=${offset.value}`);
      if (!Array.isArray(raw)) throw new Error("CHAT_RESPONSE_INVALID");
      const result = raw.map(decodeChat);
      if (stamp === ticket) chats.value = result;
    } catch (failure) {
      if (stamp === ticket) failed(failure);
    }
  }
  /** 轮询只读持久结果，不自动重试写入或调用真实模型。 */
  async function refresh(): Promise<void> {
    if (!selected.value || busy.value) return;
    const stamp = ticket,
      id = selected.value;
    try {
      await apply(stamp, decodeChatView(await api.get<unknown>(`/api/v1/chats/${id}`)));
    } catch (failure) {
      if (stamp === ticket) failed(failure);
    }
  }
  /**
   * @param id 私有会话，读取恢复完整消息及关联任务。
   */
  async function open(id: string): Promise<void> {
    if (busy.value || retryable.value) return;
    clear();
    selected.value = id;
    await refresh();
    timer = setInterval(() => {
      void refresh();
    }, 1500);
  }
  /** 新对话只清除当前选择，不删除聊天或停止任务。 */
  function start(): void {
    if (busy.value || retryable.value) return;
    if (user.value) sessionStorage.removeItem(`travel.chat.${user.value.id}`);
    clear();
  }
  /**
   * @param command 保留原输入ID的写入闭包，未知网络结果只能显式原样重试。
   */
  async function write(command: () => Promise<ChatView>): Promise<boolean> {
    if (busy.value) return false;
    const stamp = ticket;
    busy.value = true;
    error.value = "";
    try {
      const view = decodeChatView(await command());
      if (stamp !== ticket) return false;
      retry = null;
      retryable.value = false;
      await apply(stamp, view);
      await list(stamp);
      if (!timer)
        timer = setInterval(() => {
          void refresh();
        }, 1500);
      return true;
    } catch (failure) {
      if (stamp === ticket) {
        if (failure instanceof ApiError && failure.status === 0) {
          retry = command;
          retryable.value = true;
        }
        failed(failure);
      }
      return false;
    } finally {
      if (stamp === ticket) busy.value = false;
    }
  }
  /**
   * @param text 自由输入
   * @param documentIds 本轮明确选择的文件；重试闭包保留同一副本，不随草稿变化。失败时保留草稿。
   */
  async function send(text: string, documentIds: string[] = []): Promise<boolean> {
    if (retryable.value || !text.trim() || text.length > 4000) return false;
    const id = selected.value || crypto.randomUUID();
    const body = { inputId: crypto.randomUUID(), text, documentIds: [...documentIds] };
    return write(() => api.post<ChatView>(`/api/v1/chats/${id}/messages`, body));
  }
  /**
   * @param task 已存在私有旅行，将旧入口关联至同一个聊天，后续自由输入统一理解。
   */
  async function attach(task: string): Promise<void> {
    if (busy.value || retryable.value) return;
    const id = crypto.randomUUID();
    await write(() => api.post<ChatView>(`/api/v1/chats/${id}/link`, { taskId: task }));
  }
  /**
   * @param id 服务端消息
   * @param action 显式确认、撤回或同预算重试。
   */
  async function act(id: string, action: "CONFIRM" | "WITHDRAW" | "RETRY"): Promise<void> {
    if (retryable.value || !selected.value) return;
    const chat = selected.value;
    await write(() =>
      api.post<ChatView>(`/api/v1/chats/${chat}/messages/${id}/actions`, { action }),
    );
  }
  /** 明确点击时重放同一命令，不重新生成输入或预算。 */
  async function retryCommand(): Promise<void> {
    if (retry) await write(retry);
  }
  /**
   * @param direction 翻页方向，单页20条。
   */
  async function page(direction: number): Promise<void> {
    offset.value = Math.max(0, offset.value + direction * 20);
    await list();
  }
  watch(
    () => user.value?.id,
    async (id) => {
      clear();
      chats.value = [];
      offset.value = 0;
      if (!id) return;
      const stamp = ticket;
      await list(stamp);
      if (stamp !== ticket || !user.value) return;
      const saved = sessionStorage.getItem(`travel.chat.${id}`);
      if (saved) await open(saved);
    },
  );
  /** 删除当前聊天后清除迟到请求、重试闭包和会话恢复指针。 */
  function forget(): void {
    if (user.value) sessionStorage.removeItem(`travel.chat.${user.value.id}`);
    clear();
  }
  onUnmounted(clear);
  return {
    chats,
    current,
    selected,
    busy,
    error,
    retryable,
    offset,
    open,
    start,
    send,
    attach,
    act,
    refresh,
    retryCommand,
    page,
    list,
    forget,
  };
}
