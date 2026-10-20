import { test } from "node:test";
import assert from "node:assert/strict";
import { replyBlocks, replyParts } from "../src/workspace/reply.ts";

test("回复软换行和空行保留阅读语义，标题与列表不堆进用户气泡", () => {
  const blocks = replyBlocks("## 旅行建议\r\n\r\n第一段\r\n继续说明\r\n\r\n- 放慢节奏\n- 保留备选\n\n1. 确认日期\n2. 确认人数");
  assert.equal(blocks[0]?.kind, "heading");
  assert.equal(blocks[1]?.text, "第一段\n继续说明");
  assert.deepEqual(blocks[2]?.items, ["放慢节奏", "保留备选"]);
  assert.equal(blocks[3]?.ordered, true);
});
test("代码围栏内的标题、列表和HTML均保留原样，不进入正文格式解析", () => {
  const code = "# 原始代码\n- 不是列表\n<img src=\"/unsafe-image\">";
  const blocks = replyBlocks("```html\n" + code + "\n```\n\n正常正文");
  assert.deepEqual(blocks[0], { kind: "code", text: code, language: "html" });
  assert.equal(blocks[1]?.text, "正常正文");
});
test("截断或没有关闭的代码围栏不能吞掉已有内容", () => {
  assert.equal(replyBlocks("说明\n\n```java\n未完成").at(-1)?.text, "```java\n未完成");
});
test("行内格式白名单只产生文本，加粗和代码以外的标签、链接保持字面内容", () => {
  const parts = replyParts("**重要**：使用 `Day 1`，<script>bad()</script> [链接](javascript:bad())");
  assert.deepEqual(parts.slice(0, 3), [{ kind: "strong", text: "重要" }, { kind: "text", text: "：使用 " }, { kind: "code", text: "Day 1" }]);
  assert.equal(parts.at(-1)?.kind, "text");
  assert.ok(parts.at(-1)?.text.includes("<script>"));
});
test("超长回复完整退回纯文本，不截断，也不构建大量格式节点", () => {
  const text = "**长文本**".repeat(15000);
  assert.deepEqual(replyBlocks(text), [{ kind: "paragraph", text }]);
  assert.deepEqual(replyParts(text), [{ kind: "text", text }]);
});
test("旅行天次和跳号列表保留原始序号，超出HTML序号范围时显示原文", () => {
  const blocks = replyBlocks("3. 第三天\n4. 第四天\n7. 第七天\n\n999999999999999999. 原始编号");
  assert.equal(blocks[0]?.start, 3); assert.deepEqual(blocks[0]?.items, ["第三天", "第四天"]);
  assert.equal(blocks[1]?.start, 7); assert.equal(blocks[2]?.text, "999999999999999999. 原始编号");
});
