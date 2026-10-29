import { pathToFileURL } from "node:url";
import {
  CASE_IDS,
  HUMAN_CONFIGS,
  digest,
  prepareCases,
  otlpCase,
  humanSummary,
  traceAccepted,
} from "./pilot-data.mjs";

const PROJECT = "traveling-agent-evaluation";
let stage = "INIT";
let manifest;

/** 本机原生API客户端；认证不离开进程，错误不包含URL/响应/底层异常，写入不重试。 */
class NativeApi {
  #authentication;
  #count = 0;
  /** 从容器本地初始化配置读取平台Key，不读取旅行侧供应商配置。 */
  constructor() {
    const pub = process.env.LANGFUSE_INIT_PROJECT_PUBLIC_KEY,
      secret = process.env.LANGFUSE_INIT_PROJECT_SECRET_KEY;
    if (!pub || !secret) throw new Error("PILOT_AUTH_REQUIRED");
    this.#authentication = `Basic ${Buffer.from(`${pub}:${secret}`).toString("base64")}`;
  }
  /**
   * 最多150次、每次5秒/1MiB，路径仅由本文件构造。
   *
   * @param {string} path 固定原生路径
   * @param {string} method GET/POST/DELETE
   * @param {object|undefined} data 合成材料
   * @param {boolean} missing 是否允许404
   * @returns {Promise<object>} 状态与JSON
   */
  async call(path, method = "GET", data, missing = false) {
    if (++this.#count > 150) throw new Error("PILOT_REQUEST_LIMIT");
    try {
      const r = await fetch(`http://127.0.0.1:3000${path}`, {
        method,
        redirect: "error",
        signal: AbortSignal.timeout(5000),
        headers: {
          Authorization: this.#authentication,
          "Content-Type": "application/json",
          "x-langfuse-ingestion-version": "4",
        },
        body: data === undefined ? undefined : JSON.stringify(data),
      });
      if (r.status === 404 && missing) {
        await r.body?.cancel();
        return { status: 404, body: null };
      }
      if (!r.ok) {
        await r.body?.cancel();
        throw new Error("STATUS");
      }
      let length = 0;
      const chunks = [];
      if (r.body)
        for await (const part of r.body) {
          length += part.length;
          if (length > 1024 * 1024) throw new Error("SIZE");
          chunks.push(part);
        }
      const body = length ? JSON.parse(Buffer.concat(chunks, length).toString("utf8")) : {};
      if (!body || typeof body !== "object" || Array.isArray(body)) throw new Error("BODY");
      return { status: r.status, body };
    } catch {
      throw new Error("PILOT_API_UNCONFIRMED");
    }
  }
}

/**
 * 检查本机保存的清理/复核索引，只允许固定合成命名空间。
 *
 * @param {object} value 无认证的本轮索引
 * @returns {void} 非托管索引拒绝
 */
function validateManifest(value) {
  if (
    !value ||
    !/^[a-f0-9]{32}$/.test(value.id ?? "") ||
    value.name !== `ta-local-pilot-${value.id}` ||
    value.mode !== "LOCAL_CONTRACT" ||
    !Number.isFinite(Date.parse(value.startedAt)) ||
    Date.parse(value.expiresAt) - Date.parse(value.startedAt) !== 30 * 86400000 ||
    JSON.stringify(value.cases?.map((c) => c.id)) !== JSON.stringify(CASE_IDS) ||
    value.cases.some((c) => c.traceId !== digest(`${value.id}:${c.id}:trace`).slice(0, 32))
  )
    throw new Error("PILOT_MANIFEST_INVALID");
}

/**
 * 核对原生项目和本轮数据集标记，不能通过伪造索引清理其他数据。
 *
 * @param {NativeApi} api 固定本机客户端
 * @param {boolean} requireDataset 是否必须核对既有数据集，防止伪造回查索引
 * @returns {Promise<void>} 归属确认
 */
async function ownership(api, requireDataset = false) {
  const projects = (await api.call("/api/public/projects")).body;
  if (projects.data?.length !== 1 || projects.data[0].id !== PROJECT)
    throw new Error("PILOT_PROJECT_MISMATCH");
  if (manifest.datasetId || requireDataset) {
    const dataset = (await api.call(`/api/public/v2/datasets/${manifest.name}`)).body;
    if (
      (manifest.datasetId && dataset.id !== manifest.datasetId) ||
      dataset.metadata?.pilot_id !== manifest.id ||
      dataset.metadata?.mode !== "LOCAL_CONTRACT"
    )
      throw new Error("PILOT_DATASET_MISMATCH");
    manifest.datasetId = dataset.id;
  }
}

/**
 * 查询实际节点、实验关联和分数；每次只读检查，不重发业务或写入。
 *
 * @param {NativeApi} api 本机API
 * @returns {Promise<object[]>} 五例查询结果及人工状态
 */
async function inspect(api) {
  const result = [];
  for (const item of manifest.cases) {
    const observations = (
      await api.call(
        `/api/public/v2/observations?${new URLSearchParams({ traceId: item.traceId, limit: "110", fields: "core,basic,metadata" })}`,
      )
    ).body;
    const scores = (
      await api.call(
        `/api/public/v3/scores?${new URLSearchParams({ traceId: item.traceId, limit: "100", fields: "details,subject,annotation" })}`,
      )
    ).body;
    if (
      !Array.isArray(observations.data) ||
      !Array.isArray(scores.data) ||
      observations.meta?.nextCursor ||
      scores.meta?.nextCursor ||
      observations.nextCursor ||
      scores.nextCursor
    )
      throw new Error("PILOT_QUERY_INCOMPLETE");
    if (
      observations.data.some((s) => s.projectId !== PROJECT || s.traceId !== item.traceId) ||
      scores.data.some(
        (s) =>
          s.projectId !== PROJECT || s.subject?.kind !== "trace" || s.subject.id !== item.traceId,
      )
    )
      throw new Error("PILOT_QUERY_MISMATCH");
    const actualIds = new Set(observations.data.map((s) => s.id));
    const root = observations.data.find((s) => s.id === item.spanIds?.[0]);
    if (
      root &&
      (root.metadata?.mode !== "LOCAL_CONTRACT" ||
        root.metadata?.case_id !== item.id ||
        root.metadata?.suite_hash !== manifest.suiteHash)
    )
      throw new Error("PILOT_ROOT_MISMATCH");
    if (item.spanIds && observations.data.some((s) => !item.spanIds.includes(s.id)))
      throw new Error("PILOT_QUERY_MISMATCH");
    if (
      item.parents &&
      observations.data.some((s) => (s.parentObservationId ?? null) !== item.parents[s.id])
    )
      throw new Error("PILOT_PARENT_MISMATCH");
    const gates = scores.data.filter(
      (s) =>
        s.id === `pilot-gate-${manifest.id}-${item.id}` &&
        s.name === "program_hard_gate" &&
        typeof s.value === "boolean" &&
        s.dataType === "BOOLEAN" &&
        s.source === "API",
    );
    const complete =
      item.spanIds?.length === actualIds.size &&
      item.spanIds.every((id) => actualIds.has(id)) &&
      gates.length === 1;
    const program = gates.length === 1 ? (gates[0].value ? "PASS" : "FAIL") : "PENDING";
    result.push({
      caseId: item.id,
      platform: complete ? "CONFIRMED" : "PENDING",
      program,
      human: program === "PENDING" ? { status: "PENDING_PROGRAM" } : humanSummary(scores.data),
      realModel: "NOT_RUN",
      realTool: "NOT_RUN",
      traceUrl: `http://127.0.0.1:3100/project/${PROJECT}/traces/${item.traceId}`,
    });
  }
  const experiment = (
    await api.call(
      `/api/public/experiment-items?${new URLSearchParams({ experimentId: digest(manifest.id).slice(0, 32), fromStartTime: manifest.startedAt, limit: "10", fields: "core,dataset" })}`,
    )
  ).body;
  if (!Array.isArray(experiment.data) || experiment.nextCursor)
    throw new Error("PILOT_EXPERIMENT_QUERY_INCOMPLETE");
  if (
    experiment.data.some(
      (r) =>
        r.experimentDatasetId !== manifest.datasetId ||
        !manifest.cases.some(
          (c) => c.traceId === r.traceId && r.experimentItemId === `${manifest.name}-${c.id}`,
        ),
    )
  )
    throw new Error("PILOT_EXPERIMENT_MISMATCH");
  if (
    experiment.data.length !== 5 ||
    new Set(experiment.data.map((r) => r.experimentItemId)).size !== 5
  )
    for (const r of result) r.platform = "PENDING";
  return result;
}

/**
 * 首次创建本轮5例数据集并导入实际本地执行材料，未提供人工评分时不创建人工分数。
 *
 * @param {NativeApi} api 固定本机客户端
 * @param {object} bundle 本轮本地测试材料
 * @returns {Promise<object>} 原生查询状态
 */
async function publish(api, bundle) {
  const items = prepareCases(bundle);
  manifest = {
    id: bundle.id,
    name: `ta-local-pilot-${bundle.id}`,
    mode: "LOCAL_CONTRACT",
    startedAt: bundle.startedAt,
    expiresAt: new Date(Date.parse(bundle.startedAt) + 30 * 86400000).toISOString(),
    suiteHash: bundle.suite.fingerprint,
    cases: CASE_IDS.map((id) => ({ id, traceId: digest(`${bundle.id}:${id}:trace`).slice(0, 32) })),
  };
  await ownership(api);
  stage = "DATASET";
  const existing = await api.call(
    `/api/public/v2/datasets/${manifest.name}`,
    "GET",
    undefined,
    true,
  );
  if (existing.status !== 404) throw new Error("PILOT_REPLAY_DENIED");
  const dataset = (
    await api.call("/api/public/v2/datasets", "POST", {
      name: manifest.name,
      description: "Local HTTP/Worker/PG evaluation; synthetic only; not model quality",
      metadata: {
        pilot_id: manifest.id,
        mode: manifest.mode,
        original_started_at: manifest.startedAt,
        expires_at: manifest.expiresAt,
        suite_hash: manifest.suiteHash,
      },
    })
  ).body;
  if (!dataset.id || dataset.name !== manifest.name) throw new Error("PILOT_DATASET_UNCONFIRMED");
  manifest.datasetId = dataset.id;
  for (const item of items)
    await api.call("/api/public/dataset-items", "POST", {
      id: `${manifest.name}-${item.id}`,
      datasetName: manifest.name,
      input: { caseId: item.id, input: item.input },
      expectedOutput: item.oracle,
      metadata: { mode: item.mode, report_hash: item.reportHash, suite_hash: manifest.suiteHash },
    });
  stage = "SCORE_CONFIGS";
  const configs = (await api.call("/api/public/score-configs?limit=100&page=1")).body;
  if (!Array.isArray(configs.data) || configs.meta?.totalPages > 1)
    throw new Error("PILOT_CONFIG_QUERY_INCOMPLETE");
  for (const expected of HUMAN_CONFIGS) {
    const matches = configs.data.filter((c) => c.name === expected.name && !c.isArchived);
    if (
      matches.length > 1 ||
      matches.some(
        (c) =>
          c.dataType !== expected.dataType ||
          (expected.dataType === "NUMERIC" && (c.minValue !== 0 || c.maxValue !== 2)),
      )
    )
      throw new Error("PILOT_CONFIG_MISMATCH");
    if (!matches.length) await api.call("/api/public/score-configs", "POST", expected);
  }
  stage = "TRACES";
  for (const item of items) {
    const payload = otlpCase(item, manifest),
      spans = payload.resourceSpans[0].scopeSpans[0].spans;
    const entry = manifest.cases.find((c) => c.id === item.id);
    entry.spanIds = spans.map((s) => s.spanId);
    entry.parents = Object.fromEntries(spans.map((s) => [s.spanId, s.parentSpanId ?? null]));
    const ack = await api.call("/api/public/otel/v1/traces", "POST", payload);
    if (!traceAccepted(ack)) throw new Error("PILOT_TRACE_PARTIAL");
  }
  stage = "PROGRAM_SCORES";
  const ack = await api.call(
    "/api/public/scores",
    "POST",
    manifest.cases.map((item) => ({
      id: `pilot-gate-${manifest.id}-${item.id}`,
      traceId: item.traceId,
      name: "program_hard_gate",
      value: 1,
      dataType: "BOOLEAN",
      source: "API",
      timestamp: manifest.startedAt,
      environment: "local-contract",
      comment: "Local HTTP/Worker/PG deterministic checks only; not model quality or human review",
      metadata: {
        mode: manifest.mode,
        suite_hash: manifest.suiteHash,
        original_started_at: manifest.startedAt,
        expires_at: manifest.expiresAt,
      },
    })),
  );
  if (ack.status !== 202 || ack.body.message !== "Accepted") throw new Error("PILOT_SCORE_PARTIAL");
  stage = "CONFIRM";
  let rows;
  for (let i = 0; i < 8; i++) {
    rows = await inspect(api);
    if (rows.every((r) => r.platform === "CONFIRMED")) break;
    if (i < 7) await new Promise((resolve) => setTimeout(resolve, 1500));
  }
  return {
    status: rows.every((r) => r.platform === "CONFIRMED") ? "CONFIRMED" : "PENDING",
    manifest,
    rows,
  };
}

/** 维护入口只接stdin合成材料；不接受URL/Key参数，不输出平台原始错误或正文。 */
async function main() {
  let size = 0;
  const chunks = [];
  for await (const part of process.stdin) {
    size += part.length;
    if (size > 1024 * 1024) throw new Error("PILOT_INPUT_TOO_LARGE");
    chunks.push(part);
  }
  const command = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  const api = new NativeApi();
  if (command.action === "publish") return publish(api, command.bundle);
  manifest = command.manifest;
  validateManifest(manifest);
  await ownership(api, true);
  if (Date.now() >= Date.parse(manifest.expiresAt)) throw new Error("PILOT_EXPIRED");
  if (command.action === "inspect")
    return { status: "INSPECTED", manifest, rows: await inspect(api) };
  throw new Error("PILOT_ACTION_INVALID");
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main()
    .then((result) => console.log(JSON.stringify(result)))
    .catch((failure) => {
      const category = /^PILOT_[A-Z_]+$/.test(failure?.message ?? "")
        ? failure.message
        : "UNEXPECTED";
      console.log(JSON.stringify({ status: "FAILED", stage, category, manifest }));
      process.exitCode = 1;
    });
}
