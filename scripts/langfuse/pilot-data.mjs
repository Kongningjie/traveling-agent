import { createHash } from "node:crypto";

export const CASE_IDS = Object.freeze(["P01", "P02", "R01", "R02", "R03"]);
export const HUMAN_CONFIGS = Object.freeze([
  {
    name: "human.conditions",
    dataType: "NUMERIC",
    minValue: 0,
    maxValue: 2,
    description: "条件保留：0不符合，1部分符合，2符合。仅人工阅读执行材料后评分。",
  },
  {
    name: "human.facts",
    dataType: "NUMERIC",
    minValue: 0,
    maxValue: 2,
    description: "事实与引用：0不符合，1部分符合，2符合。个人资料不等于官方证据。",
  },
  {
    name: "human.clarification",
    dataType: "NUMERIC",
    minValue: 0,
    maxValue: 2,
    description: "澄清合理性：0不符合，1部分符合，2符合。",
  },
  {
    name: "human.usability",
    dataType: "NUMERIC",
    minValue: 0,
    maxValue: 2,
    description: "回复可用性：0不符合，1部分符合，2符合。",
  },
  {
    name: "human.task_completed",
    dataType: "BOOLEAN",
    description: "人工判断本场景任务是否完成；硬门禁失败不能用高分抵消。",
  },
]);

/**
 * 生成固定输入的SHA256；不把账号或认证信息作为输入。
 *
 * @param {string} text 技术身份或合成材料
 * @returns {string} 小写指纹
 */
export function digest(text) {
  return createHash("sha256").update(text).digest("hex");
}

/**
 * 判断OTLP是否完整接收；部分拒绝、未知字段和空partialSuccess均不当作成功。
 *
 * @param {object} response HTTP状态与原生JSON
 * @returns {boolean} 是否仅确认接收，仍须另查落库
 */
export function traceAccepted(response) {
  const body = response?.body;
  if (response?.status !== 200 || !body || Array.isArray(body) || typeof body !== "object")
    return false;
  if (Object.keys(body).length === 0) return true;
  // 固定4.71.0镜像非空导入实际返回BullMQ入队回执，并非OpenAPI描述的空ExportResponse。
  // 仅将这一固定结构判为“接收”，后续仍必须查询节点/分数/实验，不能当作处理成功。
  const jobKeys = [
    "name",
    "data",
    "opts",
    "id",
    "progress",
    "returnvalue",
    "stacktrace",
    "priority",
    "attemptsStarted",
    "attemptsMade",
    "stalledCounter",
    "timestamp",
    "queueQualifiedName",
  ];
  if (
    Object.keys(body).length === jobKeys.length &&
    Object.keys(body).every((k) => jobKeys.includes(k))
  ) {
    return (
      typeof body.name === "string" &&
      typeof body.id === "string" &&
      body.data != null &&
      typeof body.data === "object" &&
      Number.isFinite(body.timestamp) &&
      typeof body.queueQualifiedName === "string"
    );
  }
  if (
    Object.keys(body).some((k) => k !== "partialSuccess") ||
    !Object.hasOwn(body, "partialSuccess")
  )
    return false;
  const p = body.partialSuccess;
  return (
    p != null &&
    typeof p === "object" &&
    !Array.isArray(p) &&
    Object.keys(p).every((k) => ["rejectedSpans", "errorMessage"].includes(k)) &&
    [undefined, 0, "0"].includes(p.rejectedSpans) &&
    [undefined, ""].includes(p.errorMessage)
  );
}

/**
 * 验证由固定Java加载器和本轮隔离HTTP测试产生的合成材料，拒绝真实模式和不完整批次。
 *
 * @param {object} bundle 受控脚本组装的输入，不是任意业务报告上传接口
 * @returns {object[]} 五例的受限投影，移除账号/会话/路径和原始异常字段
 */
export function prepareCases(bundle) {
  const fail = () => {
    throw new Error("LANGFUSE_PILOT_INPUT_INVALID");
  };
  if (
    !bundle ||
    !/^[a-f0-9]{32}$/.test(bundle.id ?? "") ||
    !Number.isFinite(Date.parse(bundle.startedAt)) ||
    Date.parse(bundle.startedAt) > Date.now() ||
    Date.now() - Date.parse(bundle.startedAt) >= 30 * 86400000 ||
    !/^[a-f0-9]{64}$/.test(bundle.suite?.fingerprint ?? "") ||
    JSON.stringify(bundle.suite?.cases?.map((c) => c.id)) !== JSON.stringify(CASE_IDS) ||
    !Array.isArray(bundle.reports) ||
    bundle.reports.length !== 3
  )
    fail();
  const result = [];
  const codeHashes = new Set();
  for (const report of bundle.reports) {
    if (
      report?.mode !== "LOCAL_CONTRACT" ||
      report.modelId !== "LOCAL_INTERPRETER_STUB" ||
      report.version !== "dev35-http-v1" ||
      report.configuration?.runtime !== "SPRING_BOOT_TEST_CLASSES" ||
      !/^travel_dev35_http_[a-f0-9]{16}$/.test(report.configuration?.database ?? "") ||
      !/^[a-fA-F0-9]{64}$/.test(report.configuration?.sourceFingerprint ?? "") ||
      !Array.isArray(report.rows) ||
      !Array.isArray(report.reviews) ||
      report.rows.length !== report.reviews.length ||
      Number(report.meterTotals?.requests) !== 0 ||
      Number(report.meterTotals?.unknown) !== 0
    )
      fail();
    codeHashes.add(report.configuration.sourceFingerprint);
    for (let i = 0; i < report.rows.length; i++) {
      const row = report.rows[i],
        review = report.reviews[i];
      const source = bundle.suite.cases.find((c) => c.id === row.caseId),
        oracle = bundle.suite.oracles[row.caseId];
      if (
        !source ||
        result.some((c) => c.id === row.caseId) ||
        source.seed?.source !== "SYNTHETIC_TEST_DATA" ||
        row.mode !== "LOCAL_CONTRACT" ||
        row.status !== "PASS" ||
        row.failedChecks?.length !== 0 ||
        !oracle?.required?.length ||
        typeof source.input !== "string" ||
        source.input.length > 16000 ||
        !row.metrics ||
        Object.keys(row.metrics).length > 20 ||
        Object.entries(row.metrics).some(
          ([k, v]) => !/^\w{1,60}$/.test(k) || !Number.isSafeInteger(v) || v < 0,
        ) ||
        !Array.isArray(row.trace) ||
        row.trace.length > 32 ||
        row.trace.some((s) => typeof s !== "string" || !/^[A-Z_]{1,80}$/.test(s)) ||
        !oracle.outcome.split("|").includes(row.outcome) ||
        !Array.isArray(review.messages) ||
        review.messages.length < 1 ||
        review.messages.length > 16 ||
        !Array.isArray(review.spans) ||
        review.spans.length < 1 ||
        review.spans.length > 100
      )
        fail();
      const messages = review.messages.map((m) => {
        if (
          typeof m.text !== "string" ||
          typeof m.reply !== "string" ||
          m.text.length > 16000 ||
          m.reply.length > 16000 ||
          /\u0000/.test(m.text + m.reply)
        )
          fail();
        return { user: m.text, assistant: m.reply };
      });
      const spans = review.spans.map((s) => {
        if (
          !/^[a-f0-9-]{36}$/.test(s.id ?? "") ||
          (s.parent != null && !/^[a-f0-9-]{36}$/.test(s.parent)) ||
          !/^[A-Z_]{1,40}$/.test(s.kind ?? "") ||
          !/^[A-Z_]{1,40}$/.test(s.state ?? "") ||
          !Number.isSafeInteger(s.started) ||
          (s.ended == null
            ? s.state !== "WAITING_USER"
            : !Number.isSafeInteger(s.ended) || s.ended < s.started)
        )
          fail();
        return {
          id: s.id,
          parent: s.parent,
          kind: s.kind,
          state: s.state,
          started: s.started,
          ended: s.ended,
        };
      });
      const ids = new Set(spans.map((s) => s.id));
      if (ids.size !== spans.length || spans.some((s) => s.parent && !ids.has(s.parent))) fail();
      if (!spans.some((s) => s.ended != null)) fail();
      // 检查父链有界且无环，不能把缺失父节点静默接成根。
      for (const span of spans) {
        let p = span;
        const seen = new Set();
        while (p) {
          if (seen.has(p.id)) fail();
          seen.add(p.id);
          p = spans.find((s) => s.id === p.parent);
        }
      }
      result.push({
        id: row.caseId,
        input: source.input,
        messages,
        spans,
        oracle,
        outcome: row.outcome,
        sourceFingerprint: report.configuration.sourceFingerprint.toLowerCase(),
        reportHash: digest(JSON.stringify(report)),
        mode: "LOCAL_CONTRACT",
        programStatus: row.status,
        metrics: row.metrics,
        traceCodes: row.trace,
      });
    }
  }
  if (
    codeHashes.size !== 1 ||
    result.length !== 5 ||
    !CASE_IDS.every((id) => result.some((c) => c.id === id))
  )
    fail();
  return CASE_IDS.map((id) => result.find((c) => c.id === id));
}

/**
 * 将真实PG节点映射到本次合成case轨迹，保留原始父子关系和时间，不生成虚假的模型节点。
 *
 * @param {object} item 已验证的单例材料
 * @param {object} manifest 本轮身份/数据集定位
 * @returns {object} OTLP JSON
 */
export function otlpCase(item, manifest) {
  const traceId = digest(`${manifest.id}:${item.id}:trace`).slice(0, 32);
  const root = digest(`${manifest.id}:${item.id}:root`).slice(0, 16);
  const mapping = new Map(
    item.spans.map((s) => [s.id, digest(`${manifest.id}:${item.id}:${s.id}`).slice(0, 16)]),
  );
  if (new Set([root, ...mapping.values()]).size !== mapping.size + 1)
    throw new Error("LANGFUSE_PILOT_ID_COLLISION");
  const attr = (key, value) => ({ key, value: { stringValue: String(value) } });
  const meta = {
    mode: "LOCAL_CONTRACT",
    case_id: item.id,
    suite_hash: manifest.suiteHash,
    source_fingerprint: item.sourceFingerprint,
    program_status: item.programStatus,
    original_started_at: manifest.startedAt,
    expires_at: manifest.expiresAt,
    report_hash: item.reportHash,
    real_model_status: "NOT_RUN",
    real_tool_status: "NOT_RUN",
  };
  const attributes = [
    attr("langfuse.environment", "local-contract"),
    attr("langfuse.trace.name", `${item.id} LOCAL_CONTRACT`),
    attr("langfuse.session.id", manifest.name),
    attr("langfuse.observation.type", "span"),
    attr("langfuse.observation.input", JSON.stringify({ userInput: item.input, mode: item.mode })),
    attr(
      "langfuse.observation.output",
      JSON.stringify({
        conversation: item.messages,
        outcome: item.outcome,
        metrics: item.metrics,
        traceCodes: item.traceCodes,
      }),
    ),
    attr("langfuse.experiment.id", digest(manifest.id).slice(0, 32)),
    attr("langfuse.experiment.name", manifest.name),
    attr("langfuse.experiment.dataset.id", manifest.datasetId),
    attr("langfuse.experiment.item.id", `${manifest.name}-${item.id}`),
    attr("langfuse.experiment.item.root_observation_id", root),
    attr("langfuse.experiment.item.expected_output", JSON.stringify(item.oracle)),
    ...Object.entries(meta).flatMap(([k, v]) => [
      attr(`langfuse.trace.metadata.${k}`, v),
      attr(`langfuse.observation.metadata.${k}`, v),
    ]),
  ];
  const nanos = (value) => (BigInt(value) * 1000000n).toString();
  const spans = [
    {
      traceId,
      spanId: root,
      name: `${item.id} local HTTP evaluation`,
      kind: 1,
      startTimeUnixNano: nanos(Math.min(...item.spans.map((s) => s.started))),
      endTimeUnixNano: nanos(
        Math.max(...item.spans.filter((s) => s.ended != null).map((s) => s.ended)),
      ),
      attributes,
    },
    ...item.spans.map((s) => ({
      traceId,
      spanId: mapping.get(s.id),
      parentSpanId: s.parent ? mapping.get(s.parent) : root,
      name: s.ended == null ? `${s.kind} start event (open; duration unknown)` : s.kind,
      kind: 1,
      startTimeUnixNano: nanos(s.started),
      // 原生OTLP会为缺失结束时间补当前时间；未结束运行只投影已知开始事件，不填业务结束时间。
      endTimeUnixNano: nanos(s.ended ?? s.started),
      attributes: [
        attr("langfuse.environment", "local-contract"),
        attr("langfuse.observation.type", s.ended == null ? "event" : "span"),
        ...(s.ended == null
          ? [
              attr("langfuse.observation.metadata.source_end_time", "UNKNOWN"),
              attr("langfuse.observation.metadata.projection", "OPEN_RUN_START_EVENT"),
            ]
          : []),
        attr("langfuse.observation.metadata.execution_state", s.state),
        attr("langfuse.observation.metadata.mode", "LOCAL_CONTRACT"),
      ],
    })),
  ];
  return {
    resourceSpans: [
      {
        resource: { attributes: [attr("service.name", "traveling-agent-local-pilot")] },
        scopeSpans: [{ scope: { name: "traveling-agent-pilot-v1" }, spans }],
      },
    ],
  };
}

/**
 * 原样展示原生平台的人工分数，不合成总分、不判断人工任务通过。
 *
 * @param {object[]} scores 原生v3分数
 * @returns {object} 已记录分数或PENDING；API预填不冒充人工评分
 */
export function humanSummary(scores) {
  const values = scores
    .filter(
      (s) =>
        s.source === "ANNOTATION" &&
        typeof s.authorUserId === "string" &&
        s.authorUserId.length > 0 &&
        HUMAN_CONFIGS.some((c) => c.name === s.name),
    )
    .map((s) => ({ name: s.name, value: s.value }));
  return { status: values.length ? "RECORDED" : "PENDING", scores: values };
}
