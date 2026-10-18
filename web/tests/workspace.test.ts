import test from "node:test";
import assert from "node:assert/strict";
import { Api, ApiError, decodeUser } from "../src/workspace/client.ts";
import identity from "./fixtures/identity.json" with { type: "json" };
import {
  currentNeedsValidation,
  canAccept,
  canAnswer,
  sourceLink,
  tripError,
} from "../src/workspace/guards.ts";
import type { CandidateView, Card, View } from "../src/workspace/types.ts";

test("身份使用服务端loginName契约，剔除额外字段而不伪造用户名", () => {
  assert.deepEqual(decodeUser(identity), identity);
  assert.deepEqual(
    decodeUser({ ...identity, extra: "__SYNTHETIC_PRIVATE_FIELD__" }),
    identity,
  );
});
test("旧username形状和异常身份在渲染前拒绝，固定错误不带原始响应", () => {
  for (const value of [
    null,
    [],
    {},
    { id: identity.id, username: "__OLD_FIXTURE__" },
    { ...identity, id: "invalid" },
    { ...identity, loginName: "" },
    { ...identity, loginName: 42 },
  ]) {
    assert.throws(
      () => decodeUser(value),
      (failure: unknown) =>
        failure instanceof ApiError &&
        failure.status === 502 &&
        !failure.message.includes("__OLD_FIXTURE__"),
    );
  }
});

/** 构造当前一致视图，只有确定性门槛满足才能确认。 */
function context(): { view: View; candidate: CandidateView } {
  return {
    view: {
      taskId: "task",
      constraintRevision: 2,
      generation: 3,
      currentVersionId: "base",
      runId: "run",
      state: "WAITING_ACCEPTANCE",
      pendingInput: false,
      cursor: "task:2",
      requirements: {},
      itinerary: null,
    },
    candidate: {
      candidate: {
        candidateId: "candidate",
        version: {
          snapshotId: "snapshot",
          baseVersionId: "base",
          constraintRevision: 2,
          payloadHash: "hash",
        },
        state: "PRESENTED",
        snapshot: {} as CandidateView["candidate"]["snapshot"],
        validation: { conclusion: "PASS", issues: [] },
        review: { conclusion: "PASS", issues: [] },
      },
      costs: null,
      acceptable: true,
    },
  };
}
test("候选未同步、需求变化、未审核、阻断UNKNOWN均不能接受", () => {
  const { view, candidate } = context();
  assert.equal(canAccept(view, candidate, true), true);
  assert.equal(canAccept(view, candidate, false), false);
  assert.equal(
    canAccept({ ...view, pendingInput: true }, candidate, true),
    false,
  );
  assert.equal(
    canAccept({ ...view, constraintRevision: 3 }, candidate, true),
    false,
  );
  assert.equal(
    canAccept({ ...view, currentVersionId: "new" }, candidate, true),
    false,
  );
  assert.equal(
    canAccept(
      view,
      { ...candidate, candidate: { ...candidate.candidate, review: null } },
      true,
    ),
    false,
  );
  assert.equal(
    canAccept(
      view,
      {
        ...candidate,
        candidate: {
          ...candidate.candidate,
          validation: { conclusion: "UNKNOWN", issues: [] },
        },
      },
      true,
    ),
    false,
  );
});
test("旧运行、旧代次或旧需求卡片不能提交，推荐不是采纳", () => {
  const { view } = context();
  const card = {
    decision: {
      state: "OPEN",
      runId: "run",
      generation: 3,
      constraintRevision: 2,
    },
  } as Card;
  assert.equal(canAnswer(view, card, true), true);
  assert.equal(canAnswer({ ...view, generation: 4 }, card, true), false);
  assert.equal(canAnswer({ ...view, runId: "new" }, card, true), false);
  assert.equal(canAnswer(view, card, false), false);
});
test("一及三城市、十天范围有效，重复城市和超范围提前提示", () => {
  assert.equal(tripError("北京", "2026-11-01", "2026-11-01", 1), "");
  assert.equal(tripError("北京，天津,上海", "2026-11-01", "2026-11-10", 4), "");
  assert.ok(tripError("北京,天津,上海,南京", "2026-11-01", "2026-11-10", 4));
  assert.ok(tripError("北京,北京", "2026-11-01", "2026-11-10", 1));
  assert.ok(tripError("北京", "2026-11-01", "2026-11-11", 1));
  assert.ok(tripError("北京", "", "", 0));
});
test("证据链接拒绝脚本、文件和嵌入凭证", () => {
  assert.equal(sourceLink("javascript:alert(1)"), undefined);
  assert.equal(sourceLink("file:///tmp/source"), undefined);
  assert.equal(sourceLink("https://name:placeholder@example.test/"), undefined);
  assert.equal(
    sourceLink("https://example.test/source"),
    "https://example.test/source",
  );
});
test("每个写入先获取CSRF并携带同源Cookie策略，不持久化令牌", async () => {
  const original = globalThis.fetch;
  const calls: { path: string; options?: RequestInit }[] = [];
  globalThis.fetch = async (path, options) => {
    calls.push({ path: String(path), options });
    return new Response(
      JSON.stringify(
        String(path).endsWith("/csrf")
          ? { headerName: "X-CSRF-TOKEN", token: "__LOCAL_TEST_ONLY__" }
          : { status: "RECEIVED" },
      ),
      { status: 200 },
    );
  };
  try {
    await new Api().post("/api/v1/tasks", { requestId: "same-key" });
    assert.equal(calls.length, 2);
    assert.equal(calls[0].path, "/api/v1/auth/csrf");
    assert.equal(calls[1].options?.credentials, "same-origin");
    assert.equal(calls[1].options?.cache, "no-store");
    assert.equal(
      (calls[1].options?.headers as Record<string, string>)["X-CSRF-TOKEN"],
      "__LOCAL_TEST_ONLY__",
    );
  } finally {
    globalThis.fetch = original;
  }
});
test("网络失败不自动重试POST，不向异常输出响应正文", async () => {
  const original = globalThis.fetch;
  let posts = 0;
  globalThis.fetch = async (path) => {
    if (String(path).endsWith("/csrf"))
      return new Response(
        JSON.stringify({
          headerName: "X-CSRF-TOKEN",
          token: "__LOCAL_TEST_ONLY__",
        }),
      );
    posts++;
    throw new Error("private supplier response");
  };
  try {
    await assert.rejects(
      new Api().post("/api/v1/tasks", { requestId: "same-key" }),
      (failure: unknown) =>
        failure instanceof ApiError &&
        failure.status === 0 &&
        !failure.message.includes("private supplier"),
    );
    assert.equal(posts, 1);
  } finally {
    globalThis.fetch = original;
  }
});
test("409只返回固定说明，不回显后端正文或自动接受", async () => {
  const original = globalThis.fetch;
  globalThis.fetch = async () =>
    new Response("private server details", { status: 409 });
  try {
    await assert.rejects(
      new Api().get("/api/v1/tasks"),
      (failure: unknown) =>
        failure instanceof ApiError &&
        failure.status === 409 &&
        !failure.message.includes("private server"),
    );
  } finally {
    globalThis.fetch = original;
  }
});

test("需求更新后保留的旧行程必须提示重新校验，不能展示为满足最新要求", () => {
  const { view, candidate } = context();
  const trip = { ...candidate.candidate.snapshot, constraintRevision: 1 };
  assert.equal(currentNeedsValidation(view, trip), true);
  assert.equal(
    currentNeedsValidation({ ...view, constraintRevision: 1 }, trip),
    false,
  );
});
