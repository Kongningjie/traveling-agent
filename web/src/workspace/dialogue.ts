/** 独立聊天摘要，尚未规划时taskId为空。 */
export interface Chat {
  id: string;
  title: string;
  taskId: string | null;
}
/** 服务端构造的精确确认，不由浏览器重组操作参数。 */
export interface ChatOperation {
  action: string;
  description: string;
}
/** 本条消息选中的原件；名称来自当前权威索引，删除后不展示旧文件名。 */
export interface ChatDocument {
  id: string;
  name: string;
  available: boolean;
}
/** 输入与助手结果，状态来自持久化记录。 */
export interface ChatMessage {
  id: string;
  text: string;
  state: string;
  reply: string;
  operation: ChatOperation | null;
  error: string;
  documents?: ChatDocument[];
}
/** 同一读取中的对话与屏障。 */
export interface ChatView {
  chat: Chat;
  messages: ChatMessage[];
  blocked: boolean;
}
/**
 * @param value 未知JSON
 * @returns 对象形状，不接受null或数组。
 */
function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value))
    throw new Error("聊天响应格式无效，请同步后重试。");
  return value as Record<string, unknown>;
}
/**
 * @param value 未知字段
 * @returns UUID是否有效。
 */
function uuid(value: unknown): value is string {
  return (
    typeof value === "string" &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)
  );
}
/**
 * @param value 未知会话
 * @returns 已校验对象，字段缺失必须显式报错而不是渲染白屏。
 */
export function decodeChat(value: unknown): Chat {
  const v = object(value);
  if (!uuid(v.id) || typeof v.title !== "string" || (v.taskId !== null && !uuid(v.taskId)))
    throw new Error("聊天响应格式无效，请同步后重试。");
  return { id: v.id, title: v.title, taskId: v.taskId };
}
/**
 * @param value 未知HTTP响应
 * @returns 结构校验后的私有视图；回复仅作为文本渲染。
 */
export function decodeChatView(value: unknown): ChatView {
  const v = object(value),
    chat = decodeChat(v.chat);
  if (!Array.isArray(v.messages) || v.messages.length > 120 || typeof v.blocked !== "boolean")
    throw new Error("聊天响应格式无效，请同步后重试。");
  const messages = v.messages.map((raw): ChatMessage => {
    const m = object(raw);
    if (
      !uuid(m.id) ||
      typeof m.text !== "string" ||
      typeof m.reply !== "string" ||
      typeof m.error !== "string" ||
      !["QUEUED", "RUNNING", "DONE", "CONFIRM", "CLARIFY", "PAUSED", "WITHDRAWN"].includes(
        String(m.state),
      )
    )
      throw new Error("聊天响应格式无效，请同步后重试。");
    let operation: ChatOperation | null = null;
    if (m.operation !== null) {
      const op = object(m.operation);
      if (typeof op.action !== "string" || typeof op.description !== "string")
        throw new Error("聊天响应格式无效，请同步后重试。");
      operation = { action: op.action, description: op.description };
    }
    if (m.state === "CONFIRM" && !operation) throw new Error("聊天响应格式无效，请同步后重试。");
    const documents = m.documents === undefined ? undefined : decodeChatDocuments(m.documents);
    return {
      ...(documents === undefined ? {} : { documents }),
      id: m.id,
      text: m.text,
      state: m.state as string,
      reply: m.reply,
      error: m.error,
      operation,
    };
  });
  return { chat, messages, blocked: v.blocked };
}

/**
 * @param value 服务端附件引用
 * @returns 不含路径或正文、最多10个唯一定位引用。
 */
export function decodeChatDocuments(value: unknown): ChatDocument[] {
  if (!Array.isArray(value) || value.length > 10) throw new Error("附件响应格式无效");
  const seen = new Set<string>();
  return value.map((raw) => {
    const d = object(raw);
    if (
      !uuid(d.id) ||
      seen.has(d.id) ||
      typeof d.name !== "string" ||
      d.name.length > 160 ||
      typeof d.available !== "boolean"
    )
      throw new Error("附件响应格式无效");
    seen.add(d.id);
    return { id: d.id, name: d.name, available: d.available };
  });
}
