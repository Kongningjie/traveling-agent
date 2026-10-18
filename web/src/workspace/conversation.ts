import type { Fact } from "./types";

/**
 * 自由描述创建旅行，不在浏览器推断日期、预算或人数。
 * @param text 非空、最多4000个UTF-16单位的用户原文，正文保留空白及换行。
 * @returns 会话列表缩略标题、完整原文及空事实集；标题不代表业务需求提取。
 * @throws Error 空白或超限时固定拒绝，不在异常内附带原文。
 */
export function conversationInput(text: string): {
  title: string;
  text: string;
  facts: Fact[];
} {
  if (!text.trim() || text.length > 4000)
    throw new Error("INVALID_CONVERSATION_INPUT");
  const characters = Array.from(text.trim().replace(/\s+/g, " "));
  return {
    title:
      characters.slice(0, 36).join("") + (characters.length > 36 ? "…" : ""),
    text,
    facts: [],
  };
}
