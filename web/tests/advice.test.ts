import { test } from "node:test";
import assert from "node:assert/strict";
import { decodeAdvice, adviceFieldLabel } from "../src/workspace/advice.ts";
const id = "00000000-0000-4000-8000-000000000001";
/** 本地合成建议，不包含实际用户或服务凭据。 */
function fixture() {
  return {
    id,
    message: id,
    document: id,
    task: null,
    state: "OPEN",
    draft: {
      reference: id,
      quote: "打算去西湖",
      statement: "INTENDED",
      problem: "节奏",
      reason: "安排紧凑",
      proposal: "慢游",
      impact: "当天",
      evidence: "USER_MATERIAL",
      pending: "",
      changes: { pace: "慢游" },
    },
  };
}
test("用户材料不升级官方事实，确认回执不等于行程生效", () => {
  const item = fixture();
  assert.deepEqual(decodeAdvice([item]), [item]);
  item.state = "APPLIED";
  assert.equal(decodeAdvice([item])[0]?.state, "APPLIED");
});
test("未知状态、伪造证据级别、缺字段及超限响应拒绝", () => {
  for (const change of [
    { state: "ACCEPTED" },
    { draft: { ...fixture().draft, evidence: "VERIFIED" } },
    { draft: { ...fixture().draft, reference: "bad" } },
  ])
    assert.throws(() => decodeAdvice([{ ...fixture(), ...change }]));
  assert.throws(() => decodeAdvice(Array(41).fill(fixture())));
  assert.throws(() => decodeAdvice(null));
});
test("模型文本保持纯字符串，不解析为HTML", () => {
  const item = fixture();
  item.draft.proposal = "<script>synthetic</script>";
  assert.equal(decodeAdvice([item])[0]?.draft.proposal, item.draft.proposal);
});

test("确认字段使用业务名称", () => {
  assert.equal(adviceFieldLabel("pace"), "旅行节奏");
  assert.equal(adviceFieldLabel("unknown"), "旅行要求");
});
