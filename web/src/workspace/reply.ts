/** 回复排版块只含文本，不携带HTML、URL执行行为或工具指令。 */
export interface ReplyBlock {
  kind: "paragraph" | "heading" | "list" | "quote" | "code";
  text: string;
  level?: number;
  ordered?: boolean;
  start?: number;
  items?: string[];
  language?: string;
}
/** 行内格式白名单；所有内容仍交给Vue文本插值转义。 */
export interface ReplyPart {
  kind: "text" | "strong" | "code";
  text: string;
}

/**
 * @param text 原始助手回复
 * @returns 安全文本排版块；不完整代码围栏保留原文，不加载图片或执行HTML。
 * 超长正文降为完整纯文本，避免为展示构建大量节点；不截断或修改复制原文。
 */
export function replyBlocks(text: string): ReplyBlock[] {
  if (text.length > 100000) return [{ kind: "paragraph", text }];
  const lines = text.replace(/\r\n?/g, "\n").split("\n"),
    blocks: ReplyBlock[] = [];
  let paragraph: string[] = [],
    code: string[] | null = null,
    language = "";
  /** 连续正文合成一段，单换行保留，空行决定段间距。 */
  function flush(): void {
    if (paragraph.length) {
      blocks.push({ kind: "paragraph", text: paragraph.join("\n") });
      paragraph = [];
    }
  }
  for (const line of lines) {
    const fence = /^\s*```([^`]*)$/.exec(line);
    if (code !== null) {
      if (fence && !fence[1]?.trim()) {
        blocks.push({ kind: "code", text: code.join("\n"), language });
        code = null;
      } else code.push(line);
      continue;
    }
    if (fence) {
      flush();
      code = [];
      language = fence[1]?.trim() ?? "";
      continue;
    }
    if (!line.trim()) {
      flush();
      continue;
    }
    const heading = /^(#{1,6})\s+(.+)$/.exec(line);
    if (heading) {
      flush();
      blocks.push({
        kind: "heading",
        level: Math.min(6, heading[1]!.length + 1),
        text: heading[2]!,
      });
      continue;
    }
    const item = /^\s*(?:([-+*])|(\d+)[.)])\s+(.+)$/.exec(line);
    if (
      item &&
      (!item[2] || (Number.isSafeInteger(Number(item[2])) && Number(item[2]) <= 2147483647))
    ) {
      flush();
      const ordered = Boolean(item[2]),
        last = blocks.at(-1);
      // 行程天次和非连续序号不能被浏览器悄悄改成1、2；有间断时另起保留起点的列表。
      if (
        last?.kind === "list" &&
        last.ordered === ordered &&
        (!ordered || last.start! + last.items!.length === Number(item[2]))
      )
        last.items!.push(item[3]!);
      else
        blocks.push({
          kind: "list",
          text: "",
          ordered,
          start: ordered ? Number(item[2]) : undefined,
          items: [item[3]!],
        });
      continue;
    }
    const quote = /^>\s?(.*)$/.exec(line);
    if (quote) {
      flush();
      blocks.push({ kind: "quote", text: quote[1]! });
      continue;
    }
    paragraph.push(line);
  }
  flush();
  // 流式或异常截断的围栏不能吞掉正文；未经闭合的部分作为原始文本显示。
  if (code !== null)
    blocks.push({ kind: "paragraph", text: "```" + language + "\n" + code.join("\n") });
  return blocks;
}

/**
 * @param text 一段正文
 * @returns 仅识别完整加粗及行内代码；其他语法和HTML保持字面文本。
 */
export function replyParts(text: string): ReplyPart[] {
  if (text.length > 100000) return [{ kind: "text", text }];
  const parts: ReplyPart[] = [],
    pattern = /\*\*([^*\n]+)\*\*|`([^`\n]+)`/g;
  let start = 0;
  for (const match of text.matchAll(pattern)) {
    if (match.index > start) parts.push({ kind: "text", text: text.slice(start, match.index) });
    parts.push({ kind: match[1] === undefined ? "code" : "strong", text: match[1] ?? match[2]! });
    start = match.index + match[0].length;
  }
  if (start < text.length) parts.push({ kind: "text", text: text.slice(start) });
  return parts;
}
