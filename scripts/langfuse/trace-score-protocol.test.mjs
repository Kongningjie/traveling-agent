import assert from "node:assert/strict";
import http from "node:http";
import { once } from "node:events";
import test from "node:test";
import { datasetScope } from "./managed-datasets.mjs";
import {
  TraceScoreProtocolClient,
  traceProtocolIdentity,
  protocolTracePayload,
} from "./trace-score-protocol.mjs";
import { verifyTraceScoreOpenApi } from "./trace-score-contract.mjs";

/**
 * 创建可控回环服务；不读取本机密钥，不连接平台或模型。
 *
 * @returns {Promise<object>} 原范围、合成响应、请求记录和关闭函数
 */
async function fixture() {
  const scope = datasetScope("b".repeat(32), new Date(Date.now() - 1000).toISOString());
  const ids = traceProtocolIdentity(scope);
  const metadata = {
    namespace: "traveling-agent-lf3-trace-protocol-v1",
    run_key: scope.runKey,
    original_started_at: scope.startedAt,
    expires_at: scope.expiresAt,
  };
  const f = {
    scope,
    ids,
    calls: [],
    project: "test-project",
    traceStatus: 200,
    traceBody: {},
    scoreStatus: 202,
    scoreBody: { message: "Accepted" },
    observations: {
      data: [
        {
          id: ids.root,
          traceId: ids.trace,
          parentObservationId: null,
          projectId: "test-project",
          type: "SPAN",
          environment: "local-protocol",
          name: "protocol.root",
          metadata,
        },
        {
          id: ids.child,
          traceId: ids.trace,
          parentObservationId: ids.root,
          projectId: "test-project",
          type: "SPAN",
          environment: "local-protocol",
          name: "protocol.child",
          metadata,
        },
      ],
    },
    scores: {
      data: [
        {
          id: ids.score,
          projectId: "test-project",
          name: "protocol_transport_check",
          value: 1,
          dataType: "NUMERIC",
          source: "API",
          environment: "local-protocol",
          timestamp: scope.startedAt,
          subject: { kind: "trace", id: ids.trace },
          metadata,
        },
      ],
    },
  };
  const server = http.createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = Buffer.concat(chunks).toString("utf8");
    f.calls.push({
      path: req.url,
      method: req.method,
      body: body ? JSON.parse(body) : null,
      version: req.headers["x-langfuse-ingestion-version"],
    });
    f.onRequest?.(req);
    let value;
    let status = 200;
    if (f.mode === "redirect") {
      res.writeHead(302, { Location: "http://untrusted.invalid/" }).end();
      return;
    }
    if (f.mode === "large") {
      res.writeHead(200).end("x".repeat(129 * 1024));
      return;
    }
    if (req.url === "/api/public/projects") value = { data: [{ id: f.project }] };
    else if (req.url === "/api/public/otel/v1/traces") {
      value = f.traceBody;
      status = f.traceStatus;
    } else if (req.url === "/api/public/scores") {
      value = f.scoreBody;
      status = f.scoreStatus;
    } else if (req.url.startsWith("/api/public/v2/observations?")) value = f.observations;
    else if (req.url.startsWith("/api/public/v3/scores?")) value = f.scores;
    else {
      status = 404;
      value = {};
    }
    res.writeHead(status, { "Content-Type": "application/json" }).end(JSON.stringify(value));
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  f.client = new TraceScoreProtocolClient({
    endpoint: `http://127.0.0.1:${server.address().port}/`,
    projectId: "test-project",
    publicKey: "synthetic-public",
    secretKey: "synthetic-private",
  });
  f.close = () => {
    server.closeAllConnections();
    server.close();
  };
  return f;
}

/**
 * 按依赖发送一次轨迹和技术分数，供只读确认测试使用。
 *
 * @param {object} f 合成服务
 * @returns {Promise<void>} 只接受投递结果，未做落库确认
 */
async function accepted(f) {
  assert.equal(await f.client.submitTrace(f.scope), "ACCEPTED");
  assert.equal(await f.client.submitScore(f.scope), "ACCEPTED");
}

test("协议ID稳定、根子节点不同，OTLP保留父子关系且无模型正文或虚构计量", async () => {
  const f = await fixture();
  try {
    assert.deepEqual(traceProtocolIdentity(f.scope), f.ids);
    assert.notEqual(f.ids.root, f.ids.child);
    const payload = protocolTracePayload(f.scope);
    const spans = payload.resourceSpans[0].scopeSpans[0].spans;
    assert.equal(spans[1].parentSpanId, spans[0].spanId);
    assert.equal(spans[0].traceId.length, 32);
    assert.equal(spans[1].spanId.length, 16);
    assert.equal(/input|output|token|cost|model/i.test(JSON.stringify(payload)), false);
    await accepted(f);
    assert.equal(f.calls.find((c) => c.path.endsWith("/traces")).version, "4");
    const batch = f.calls.find((c) => c.path === "/api/public/scores").body;
    assert.equal(batch.length, 1);
    assert.equal(batch[0].source, "API");
    assert.equal(batch[0].timestamp, f.scope.startedAt);
  } finally {
    f.close();
  }
});

test("只允许回环地址，错误不回显非法URL或认证", () => {
  for (const endpoint of [
    "https://example.com/",
    "http://web/",
    "http://127.0.0.1/a",
    "http://private:secret@127.0.0.1/",
    "not-a-url",
  ]) {
    assert.throws(
      () =>
        new TraceScoreProtocolClient({
          endpoint,
          projectId: "test",
          publicKey: "test",
          secretKey: "test",
        }),
      { message: "LANGFUSE_TRACE_PROTOCOL_CONFIG_INVALID" },
    );
  }
});

test("项目不匹配时只读项目，不发送轨迹或分数", async () => {
  const f = await fixture();
  try {
    f.project = "foreign-project";
    await assert.rejects(f.client.submitTrace(f.scope), /PROJECT_MISMATCH/);
    assert.equal(f.calls.filter((c) => c.method === "POST").length, 0);
  } finally {
    f.close();
  }
});

test("HTTP接收不等于可查询，空列表保持PENDING，随后只读确认可成功", async () => {
  const f = await fixture();
  try {
    await accepted(f);
    const saved = f.scores;
    f.scores = { data: [] };
    assert.equal(await f.client.confirm(f.scope), "PENDING");
    f.scores = saved;
    assert.equal(await f.client.confirm(f.scope), "CONFIRMED");
    assert.equal(f.calls.filter((c) => c.method === "POST").length, 2);
    for (const call of f.calls.filter((c) => c.path.includes("?"))) {
      const url = new URL(call.path, "http://127.0.0.1");
      assert.equal(url.searchParams.get("traceId"), f.ids.trace);
      assert.equal(url.searchParams.get("fields").includes("io"), false);
    }
  } finally {
    f.close();
  }
});

test("轨迹部分失败、HTTP失败或非法ACK不能继续评分或自动重发", async () => {
  for (const [status, body] of [
    [200, { partialSuccess: { rejectedSpans: "1" } }],
    [200, { partialSuccess: { errorMessage: "synthetic-private-error" } }],
    [200, { unknown: true }],
    [200, { partialSuccess: null }],
    [500, { detail: "synthetic-private-error" }],
    [200, null],
  ]) {
    const f = await fixture();
    try {
      f.traceStatus = status;
      f.traceBody = body;
      await assert.rejects(
        f.client.submitTrace(f.scope),
        /LANGFUSE_TRACE_PROTOCOL_(PARTIAL_OR_UNKNOWN|REQUEST_UNCONFIRMED)/,
      );
      await assert.rejects(f.client.submitTrace(f.scope), /REPLAY_BLOCKED/);
      await assert.rejects(f.client.submitScore(f.scope), /REPLAY_BLOCKED/);
      assert.equal(f.calls.filter((c) => c.method === "POST").length, 1);
    } finally {
      f.close();
    }
  }
});

test("OpenAPI缺失批量分数类型或关键查询字段时拒绝适配", () => {
  const schema = [
    "  /api/public/otel/v1/traces:",
    "    post:",
    "      application/json:",
    "      x-langfuse-ingestion-version:",
    "      resourceSpans:",
    "  /api/public/scores:",
    "    post:",
    "      CreateScoresRequest 202 207",
    "  /api/public/v3/scores:",
    "    get:",
    "      - name: id",
    "      - name: traceId",
    "      - name: fields",
    "      - name: limit",
    "  /api/public/v2/observations:",
    "    get:",
    "      - name: traceId",
    "      - name: fields",
    "      - name: expandMetadata",
    "    CreateScoresRequest:",
    "      type: array",
    "      CreateScoreRequest",
    "    CreateScoreRequest:",
    "        traceId:",
    "        timestamp:",
    "        source:",
    "        metadata:",
    "        dataType:",
    "",
  ].join("\n");
  verifyTraceScoreOpenApi(schema);
  for (const invalid of [
    schema.replace("type: array", "type: object"),
    schema.replace("expandMetadata", "unknown"),
    schema.replace("/api/public/v3/scores", "/api/public/v1/scores"),
    null,
  ]) {
    assert.throws(() => verifyTraceScoreOpenApi(invalid), /SCHEMA_UNSUPPORTED/);
  }
});

test("分数207混合接收、错误或伪成功不自动重发，也不宣称查询已确认", async () => {
  for (const [status, body] of [
    [207, { accepted: 0, rejected: 1, errors: [] }],
    [200, { message: "Accepted" }],
    [202, { message: "Accepted", errors: [] }],
    [500, {}],
  ]) {
    const f = await fixture();
    try {
      await f.client.submitTrace(f.scope);
      f.scoreStatus = status;
      f.scoreBody = body;
      await assert.rejects(
        f.client.submitScore(f.scope),
        /LANGFUSE_TRACE_PROTOCOL_(PARTIAL_OR_UNKNOWN|REQUEST_UNCONFIRMED)/,
      );
      await assert.rejects(f.client.submitScore(f.scope), /REPLAY_BLOCKED/);
      await assert.rejects(f.client.confirm(f.scope), /NOT_ACCEPTED/);
      assert.equal(f.calls.filter((c) => c.path === "/api/public/scores").length, 1);
    } finally {
      f.close();
    }
  }
});

test("节点归属、父子关系、重复节点或多余节点不匹配时拒绝确认", async () => {
  const mutations = [
    (f) => {
      f.observations.data[1].parentObservationId = "foreign";
    },
    (f) => {
      f.observations.data[0].projectId = "foreign";
    },
    (f) => {
      f.observations.data[0].metadata = {};
    },
    (f) => {
      f.observations.data[0].traceId = "foreign";
    },
    (f) => {
      f.observations.data[1] = f.observations.data[0];
    },
    (f) => {
      f.observations.data.push(f.observations.data[0]);
    },
    (f) => {
      f.observations.meta = { nextCursor: "more" };
    },
    (f) => {
      f.observations.data[0] = null;
    },
  ];
  for (const mutate of mutations) {
    const f = await fixture();
    try {
      await accepted(f);
      mutate(f);
      await assert.rejects(f.client.confirm(f.scope), /CONFIRM_MISMATCH/);
    } finally {
      f.close();
    }
  }
});

test("分数值、来源、原始时间、对象或元数据不符时拒绝确认", async () => {
  const mutations = [
    (row) => {
      row.value = 0;
    },
    (row) => {
      row.source = "ANNOTATION";
    },
    (row) => {
      row.subject = { kind: "session", id: row.subject.id };
    },
    (row) => {
      row.projectId = "foreign";
    },
    (row) => {
      row.timestamp = "2020-01-01T00:00:00Z";
    },
    (row) => {
      row.metadata = { ...row.metadata, extra: "not-allowed" };
    },
  ];
  for (const mutate of mutations) {
    const f = await fixture();
    try {
      await accepted(f);
      mutate(f.scores.data[0]);
      await assert.rejects(f.client.confirm(f.scope), /CONFIRM_MISMATCH/);
    } finally {
      f.close();
    }
  }
});

test("重复已接收写入、未投递评分和修改原始期限均拒绝", async () => {
  const f = await fixture();
  try {
    await assert.rejects(f.client.submitScore(f.scope), /SCOPE_MISMATCH/);
    await accepted(f);
    await assert.rejects(f.client.submitTrace(f.scope), /REPLAY_BLOCKED/);
    await assert.rejects(f.client.submitScore(f.scope), /REPLAY_BLOCKED/);
    const changed = datasetScope(f.scope.runKey, new Date(Date.now() - 500).toISOString());
    await assert.rejects(f.client.confirm(changed), /SCOPE_MISMATCH/);
    const expired = datasetScope(
      "c".repeat(32),
      new Date(Date.now() - 31 * 86400000).toISOString(),
    );
    await assert.rejects(f.client.submitTrace(expired), /SCOPE_INVALID/);
    assert.equal(f.calls.filter((c) => c.method === "POST").length, 2);
  } finally {
    f.close();
  }
});

test("零拒绝OTLP回执可接收，空评分行拒绝确认", async () => {
  const f = await fixture();
  try {
    f.traceBody = { partialSuccess: { rejectedSpans: "0", errorMessage: "" } };
    await accepted(f);
    f.scores.data = [null];
    await assert.rejects(f.client.confirm(f.scope), {
      message: "LANGFUSE_TRACE_PROTOCOL_CONFIRM_MISMATCH",
    });
  } finally {
    f.close();
  }
});

test("请求期间跨过到期点，不继续投递或返回确认成功", async () => {
  const realNow = Date.now;
  for (const target of [
    "/api/public/projects",
    "/api/public/v2/observations?",
    "/api/public/v3/scores?",
  ]) {
    const f = await fixture();
    try {
      if (target !== "/api/public/projects") await accepted(f);
      f.onRequest = (req) => {
        if (req.url.startsWith(target)) Date.now = () => Date.parse(f.scope.expiresAt);
      };
      const result =
        target === "/api/public/projects"
          ? f.client.submitTrace(f.scope)
          : f.client.confirm(f.scope);
      await assert.rejects(result, /SCOPE_INVALID/);
      assert.equal(
        f.calls.filter((call) => call.method === "POST").length,
        target === "/api/public/projects" ? 0 : 2,
      );
      if (target === "/api/public/v2/observations?") {
        assert.equal(
          f.calls.some((call) => call.path.startsWith("/api/public/v3/scores?")),
          false,
        );
      }
    } finally {
      Date.now = realNow;
      f.close();
    }
  }
});

test("重定向、超大响应拒绝，认证不被序列化，每实例最多40次请求", async () => {
  const f = await fixture();
  try {
    assert.equal(JSON.stringify(f.client).includes("synthetic-private"), false);
    for (const mode of ["redirect", "large"]) {
      f.mode = mode;
      await assert.rejects(f.client.verifyProject(), {
        message: "LANGFUSE_TRACE_PROTOCOL_REQUEST_UNCONFIRMED",
      });
    }
    f.mode = null;
    for (let i = 0; i < 38; i++) await f.client.verifyProject();
    await assert.rejects(f.client.verifyProject(), /REQUEST_LIMIT/);
    assert.equal(f.calls.length, 40);
  } finally {
    f.close();
  }
});
