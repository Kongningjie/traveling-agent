import { test } from "node:test";
import assert from "node:assert/strict";
import { decodeChat, decodeChatView } from "../src/workspace/dialogue.ts";

const id = "00000000-0000-4000-8000-000000000001";
/** 构造显式本地合成响应，不包含真实用户数据。 */
function fixture() { return { chat: { id, title: "咨询", taskId: null }, blocked: false, messages: [{ id, text: "你好", state: "DONE", reply: "你好，可以聊聊旅行想法。", operation: null, error: "" }] }; }
test("独立咨询允许无任务，回复保留为普通文本", () => {
  const view = fixture(); view.messages[0]!.reply = "<script>synthetic</script>";
  assert.deepEqual(decodeChatView(view), view);
});
test("确认消息必须有明确的服务端操作描述", () => {
  const view = fixture(); view.messages[0]!.state = "CONFIRM";
  assert.throws(() => decodeChatView(view), /响应格式/);
  const input = { ...view, messages: [{ ...view.messages[0], operation: { action: "ACCEPT", description: "接受指定候选" } }] };
  assert.equal(decodeChatView(input).messages[0]?.operation?.action, "ACCEPT");
});
test("错误身份字段、缺失状态及未知形状不能进入聊天界面", () => {
  for (const value of [null, [], { id, title: "咨询" }, { id: "wrong", title: "咨询", taskId: null }]) assert.throws(() => decodeChat(value));
  const view = fixture(); view.messages[0]!.state = "GUESSED";
  assert.throws(() => decodeChatView(view));
  assert.throws(() => decodeChatView({ ...fixture(), blocked: "false" }));
});
