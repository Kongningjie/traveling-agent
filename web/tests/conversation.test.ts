import test from "node:test";
import assert from "node:assert/strict";
import { conversationInput } from "../src/workspace/conversation.ts";
import { replyMode } from "../src/workspace/guards.ts";
import type { Card, View } from "../src/workspace/types.ts";

/**
 * @returns 只包含路由版本事实的纯内存夹具，不连接真实服务或假冒模型解析。
 */
function waiting(): { view: View; card: Card } {
  return {
    view: {
      taskId: "task",
      runId: "run",
      generation: 2,
      constraintRevision: 3,
      state: "WAITING_USER",
      pendingInput: false,
      cursor: "task:1",
      currentVersionId: null,
      itinerary: null,
      requirements: {},
    },
    card: {
      decision: {
        id: "decision",
        runId: "run",
        generation: 2,
        constraintRevision: 3,
        revision: 1,
        state: "OPEN",
        remaining: {},
        allowedAuthorizationScope: [],
      },
      specs: {},
    },
  };
}
test("只有模糊想法也可开聊，不预填预算、人数、时间或已订状态", () => {
  const body = conversationInput("还没想好去哪，想放松一下，也有自己的想法");
  assert.equal(body.text, "还没想好去哪，想放松一下，也有自己的想法");
  assert.deepEqual(body.facts, []);
  assert.ok(body.title.length > 0);
});
test("自然描述保留完整原文，列表标题不截断Unicode字符且不冒充需求提取", () => {
  const text = "  想和朋友去杭州\n但日期未定，预算不是上限，想先聊聊。" + "🚉".repeat(50);
  const body = conversationInput(text);
  assert.equal(body.text, text);
  assert.equal(Array.from(body.title).length, 37);
  assert.ok(body.title.endsWith("…"));
  assert.equal(body.title.includes("\n"), false);
  assert.deepEqual(body.facts, []);
});
test("拒绝空白和超长原文，不默默截断用户想法", () => {
  for (const text of ["", " \n\t", "旅".repeat(4001)]) assert.throws(() => conversationInput(text));
  assert.equal(conversationInput("旅".repeat(4000)).text.length, 4000);
});
test("有效追问的自由回答仍走原问题，不取代原运行", () => {
  const { view, card } = waiting();
  assert.equal(replyMode(view, card, true), "ANSWER");
  assert.equal(replyMode(view, card, false), "SYNCING");
});
test("旧卡片、关闭卡片和正在提交的新需求都不能借自由输入绕过版本门禁", () => {
  const { view, card } = waiting();
  assert.equal(replyMode({ ...view, generation: 3 }, card, true), "SYNCING");
  assert.equal(replyMode({ ...view, runId: "another" }, card, true), "SYNCING");
  assert.equal(replyMode({ ...view, constraintRevision: 4 }, card, true), "SYNCING");
  assert.equal(replyMode({ ...view, pendingInput: true }, card, true), "SYNCING");
  assert.equal(
    replyMode(view, { ...card, decision: { ...card.decision, state: "CLOSED" } }, true),
    "SYNCING",
  );
});
test("后续补充是消息，不将自由文本或建议点击解释为接受候选或授权", () => {
  const { view, card } = waiting();
  for (const state of ["RUNNING", "WAITING_ACCEPTANCE", "PAUSED_QUOTA", "SUCCEEDED"])
    assert.equal(replyMode({ ...view, state }, card, true), "MESSAGE");
  assert.equal(replyMode(null, null, false), "SYNCING");
});
