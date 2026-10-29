import assert from "node:assert/strict";
import test from "node:test";
import {
  CASE_IDS,
  HUMAN_CONFIGS,
  prepareCases,
  otlpCase,
  humanSummary,
  digest,
  traceAccepted,
} from "./pilot-data.mjs";

/**
 * 构造纯本地导入协议夹具，不代表真实五例执行成绩。
 *
 * @returns {object} 合成数据和程序结果的测试替身
 */
function fixture() {
  const started = Date.now() - 1000;
  const cases = CASE_IDS.map((id) => ({
    id,
    input: "合成旅行咨询",
    seed: { source: "SYNTHETIC_TEST_DATA" },
  }));
  const reports = [CASE_IDS.slice(0, 2), CASE_IDS.slice(2, 3), CASE_IDS.slice(3)].map((ids) => ({
    version: "dev35-http-v1",
    mode: "LOCAL_CONTRACT",
    modelId: "LOCAL_INTERPRETER_STUB",
    configuration: {
      runtime: "SPRING_BOOT_TEST_CLASSES",
      sourceFingerprint: "a".repeat(64),
      database: "travel_dev35_http_" + "a".repeat(16),
    },
    meterTotals: { requests: 0, unknown: 0 },
    rows: ids.map((caseId) => ({
      caseId,
      mode: "LOCAL_CONTRACT",
      status: "PASS",
      failedChecks: [],
      outcome: "COMPLETED",
      metrics: { actualModelRequests: 0 },
      trace: ["PG_SNAPSHOT"],
    })),
    reviews: ids.map(() => ({
      user: "not-exported-user",
      chat: "not-exported-chat",
      state: { private: "not-exported-state" },
      messages: [{ text: "合成输入", reply: "合成替身回复" }],
      spans: [
        {
          id: "10000000-0000-0000-0000-000000000000",
          parent: null,
          kind: "INPUT",
          state: "COMPLETED",
          started,
          ended: started + 1,
        },
        {
          id: "20000000-0000-0000-0000-000000000000",
          parent: "10000000-0000-0000-0000-000000000000",
          kind: "AGENT",
          state: "COMPLETED",
          started,
          ended: started + 1,
        },
      ],
    })),
  }));
  return {
    id: "b".repeat(32),
    startedAt: new Date(started).toISOString(),
    suite: {
      fingerprint: "c".repeat(64),
      cases,
      oracles: Object.fromEntries(
        CASE_IDS.map((id) => [id, { outcome: "COMPLETED", required: ["OWNER_ISOLATED"] }]),
      ),
    },
    reports,
  };
}

test("仅投影五例合成材料，原始账号/会话/状态不进入OTLP", () => {
  const bundle = fixture(),
    items = prepareCases(bundle);
  assert.deepEqual(
    items.map((i) => i.id),
    CASE_IDS,
  );
  const result = JSON.stringify(items);
  assert.equal(result.includes("not-exported"), false);
  assert.equal(result.includes("合成替身回复"), true);
});

test("真实模式、未知用量、混合源码指纹或不完整五例拒绝导入", () => {
  const mutations = [
    (b) => {
      b.reports[0].mode = "REAL_MODEL";
    },
    (b) => {
      b.reports[0].meterTotals.requests = 1;
    },
    (b) => {
      b.reports[0].meterTotals.unknown = 1;
    },
    (b) => {
      b.reports[0].configuration.sourceFingerprint = "d".repeat(64);
    },
    (b) => {
      b.reports[0].rows[0].status = "FAIL";
    },
    (b) => {
      b.reports[0].rows[0].failedChecks = ["OWNER_ISOLATED"];
    },
    (b) => {
      b.suite.cases[0].seed.source = "USER_DATA";
    },
    (b) => {
      b.reports.pop();
    },
  ];
  for (const mutate of mutations) {
    const b = fixture();
    mutate(b);
    assert.throws(() => prepareCases(b), /INPUT_INVALID/);
  }
});

test("缺失父节点、重复身份或父链环拒绝，不能静默修补执行链", () => {
  const mutations = [
    (b) => {
      b.reports[0].reviews[0].spans[1].parent = "90000000-0000-0000-0000-000000000000";
    },
    (b) => {
      b.reports[0].reviews[0].spans[1].id = b.reports[0].reviews[0].spans[0].id;
    },
    (b) => {
      b.reports[0].reviews[0].spans[0].parent = b.reports[0].reviews[0].spans[1].id;
    },
  ];
  for (const mutate of mutations) {
    const b = fixture();
    mutate(b);
    assert.throws(() => prepareCases(b), /INPUT_INVALID/);
  }
});

test("WAITING_USER保留未知结束时间，其他未结束状态或逆序时间拒绝", () => {
  const b = fixture();
  b.reports[0].reviews[0].spans[1].ended = null;
  b.reports[0].reviews[0].spans[1].state = "WAITING_USER";
  const items = prepareCases(b);
  const payload = otlpCase(items[0], {
    id: b.id,
    name: "test",
    datasetId: "dataset",
    suiteHash: b.suite.fingerprint,
    startedAt: b.startedAt,
    expiresAt: new Date(Date.now() + 86400000).toISOString(),
  });
  const spans = payload.resourceSpans[0].scopeSpans[0].spans;
  assert.equal(spans[2].endTimeUnixNano, spans[2].startTimeUnixNano);
  assert.equal(
    spans[2].attributes.find((a) => a.key === "langfuse.observation.type").value.stringValue,
    "event",
  );
  assert.equal(
    spans[2].attributes.find((a) => a.key === "langfuse.observation.metadata.source_end_time").value
      .stringValue,
    "UNKNOWN",
  );
  assert.equal(spans[2].parentSpanId, spans[1].spanId);
  b.reports[0].reviews[0].spans[1].state = "RUNNING";
  assert.throws(() => prepareCases(b), /INPUT_INVALID/);
});

test("OTLP使用原始PG时间和父子身份，实际无模型调用时不生成虚假GENERATION", () => {
  const b = fixture(),
    item = prepareCases(b)[0];
  const m = {
    id: b.id,
    name: "test",
    datasetId: "dataset",
    suiteHash: b.suite.fingerprint,
    startedAt: b.startedAt,
    expiresAt: new Date(Date.now() + 86400000).toISOString(),
  };
  const spans = otlpCase(item, m).resourceSpans[0].scopeSpans[0].spans;
  assert.equal(spans.length, 3);
  assert.equal(spans[1].parentSpanId, spans[0].spanId);
  assert.equal(spans[2].parentSpanId, spans[1].spanId);
  assert.equal(spans[1].startTimeUnixNano, (BigInt(item.spans[0].started) * 1000000n).toString());
  assert.equal(JSON.stringify(spans).includes("GENERATION"), false);
  assert.equal(spans[0].traceId, digest(`${b.id}:P01:trace`).slice(0, 32));
});

test("没有人工评分或只有API预填时保持PENDING，不把程序通过换算为人工满分", () => {
  assert.equal(humanSummary([], true).status, "PENDING");
  const fake = HUMAN_CONFIGS.map((c) => ({
    name: c.name,
    source: "API",
    value: 2,
    dataType: c.dataType,
  }));
  assert.equal(humanSummary(fake, true).status, "PENDING");
  for (const s of fake) s.source = "ANNOTATION";
  assert.equal(humanSummary(fake, true).status, "PENDING");
});

test("部分接收和未知ACK拒绝，不能据此继续写程序分数", () => {
  assert.equal(traceAccepted({ status: 200, body: {} }), true);
  assert.equal(
    traceAccepted({ status: 200, body: { partialSuccess: { rejectedSpans: "0" } } }),
    true,
  );
  for (const response of [
    { status: 207, body: {} },
    { status: 200, body: { partialSuccess: null } },
    { status: 200, body: { partialSuccess: { rejectedSpans: "1" } } },
    { status: 200, body: { extra: true } },
  ])
    assert.equal(traceAccepted(response), false);
});

test("固定镜像的完整入队回执只确认接收，缺失结构拒绝", () => {
  const body = {
    name: "synthetic-ingestion",
    data: {},
    opts: {},
    id: "synthetic-job",
    progress: 0,
    returnvalue: null,
    stacktrace: [],
    priority: 0,
    attemptsStarted: 0,
    attemptsMade: 0,
    stalledCounter: 0,
    timestamp: 1,
    queueQualifiedName: "synthetic-queue",
  };
  assert.equal(traceAccepted({ status: 200, body }), true);
  delete body.data;
  assert.equal(traceAccepted({ status: 200, body }), false);
});

test("原样展示人工分数，不自动平均或生成通过结论", () => {
  const score = {
    name: HUMAN_CONFIGS[0].name,
    source: "ANNOTATION",
    authorUserId: "synthetic-reviewer",
    value: 1,
  };
  const result = humanSummary([score, { ...score, value: 2 }]);
  assert.equal(result.status, "RECORDED");
  assert.deepEqual(
    result.scores.map((s) => s.value),
    [1, 2],
  );
});
