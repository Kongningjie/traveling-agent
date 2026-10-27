import { test } from "node:test";
import assert from "node:assert/strict";
import {
  feedbackCommand,
  savedProblem,
  feedbackLabel,
  problemStateLabel,
} from "../src/workspace/support.ts";
const id = "00000000-0000-4000-8000-000000000001";
test("默认反馈投影排除会话、文件及浏览器上下文，不默认授权", () => {
  const target = {
    kind: "REPLY" as const,
    scope: id,
    id,
    fullText: "不应复制的合成会话",
    cookie: "合成无效字段",
  };
  assert.deepEqual(feedbackCommand(id, target, "OTHER", "用户主动说明"), {
    id,
    target: { kind: "REPLY", scope: id, id },
    category: "OTHER",
    description: "用户主动说明",
    consent: false,
    reference: null,
  });
});
test("未保存或无效回执不能展示已记录问题编号", () => {
  for (const value of [
    null,
    {},
    { saved: false, problem: id },
    { saved: true, problem: "unknown" },
    { saved: "true", problem: id },
  ])
    assert.equal(savedProblem(value), "");
  assert.equal(savedProblem({ saved: true, problem: id }), id);
});
test("超限说明、未知目标及无效ID在提交前拒绝", () => {
  assert.throws(() =>
    feedbackCommand(id, { kind: "RUN", scope: id, id }, "OTHER", "x".repeat(1001)),
  );
  assert.throws(() => feedbackCommand("bad", { kind: "RUN", scope: id, id }, "OTHER", ""));
});

test("用户进度使用中文，已修复与已验证明确区分", () => {
  assert.equal(feedbackLabel("OTHER"), "其他");
  assert.equal(problemStateLabel("RESOLVED"), "已修复，待验证");
  assert.equal(problemStateLabel("VERIFIED"), "已验证");
  assert.equal(problemStateLabel("unknown"), "状态待同步");
});
