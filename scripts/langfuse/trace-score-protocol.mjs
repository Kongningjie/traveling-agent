import { createHash } from "node:crypto";
import { validateDatasetScope } from "./managed-datasets.mjs";

const NAMESPACE = "traveling-agent-lf3-trace-protocol-v1";
const ENVIRONMENT = "local-protocol";
const SCORE_NAME = "protocol_transport_check";

/**
 * 生成协议探针的固定身份；与业务身份隔离，不截断业务UUID充当span ID。
 *
 * @param {object} scope 已校验的随机协议范围
 * @returns {Readonly<object>} 两个不同的span和一个技术分数，不代表人工评测结果
 */
export function traceProtocolIdentity(scope) {
  validateDatasetScope(scope);
  const hash = (role) =>
    createHash("sha256").update(`${NAMESPACE}:${scope.runKey}:${role}`).digest("hex");
  const root = hash("root").slice(0, 16);
  const child = hash("child").slice(0, 16);
  const trace = hash("trace").slice(0, 32);
  if (root === child || /^0+$/.test(root) || /^0+$/.test(child) || /^0+$/.test(trace)) {
    throw new Error("LANGFUSE_TRACE_PROTOCOL_ID_INVALID");
  }
  return Object.freeze({ trace, root, child, score: `ta-protocol-${hash("score").slice(0, 32)}` });
}

/**
 * 构造无正文OTLP JSON；没有模型请求、用量或费用，不伪造模型调用。
 *
 * @param {object} scope 原始范围，必须仍在有效期
 * @returns {object} 根节点和一个子节点的OTLP请求
 */
export function protocolTracePayload(scope) {
  validateDatasetScope(scope, true);
  const ids = traceProtocolIdentity(scope);
  const start = BigInt(Date.parse(scope.startedAt)) * 1_000_000n;
  const attributes = [
    ["langfuse.environment", ENVIRONMENT],
    ["langfuse.trace.name", "Local protocol trace"],
    ["langfuse.observation.type", "SPAN"],
    ["langfuse.observation.metadata.namespace", NAMESPACE],
    ["langfuse.observation.metadata.run_key", scope.runKey],
    ["langfuse.observation.metadata.original_started_at", scope.startedAt],
    ["langfuse.observation.metadata.expires_at", scope.expiresAt],
  ].map(([key, value]) => ({ key, value: { stringValue: value } }));
  return {
    resourceSpans: [
      {
        resource: {
          attributes: [{ key: "service.name", value: { stringValue: "traveling-agent-protocol" } }],
        },
        scopeSpans: [
          {
            scope: { name: NAMESPACE },
            spans: [
              {
                traceId: ids.trace,
                spanId: ids.root,
                name: "protocol.root",
                kind: 1,
                startTimeUnixNano: start.toString(),
                endTimeUnixNano: (start + 2_000_000n).toString(),
                attributes,
              },
              {
                traceId: ids.trace,
                spanId: ids.child,
                parentSpanId: ids.root,
                name: "protocol.child",
                kind: 1,
                startTimeUnixNano: start.toString(),
                endTimeUnixNano: (start + 1_000_000n).toString(),
                attributes,
              },
            ],
          },
        ],
      },
    ],
  };
}

/**
 * 本地协议适配器；只支持技术探针，不接正式评分、业务正文或生产调用。
 * 单实例拒绝重复写；跨重启防重放须由后续PG发布台账实现，不能据此开放生产。
 */
export class TraceScoreProtocolClient {
  #origin;
  #authentication;
  #project;
  #requests = 0;
  #runs = new Map();
  #verified = false;

  /**
   * 仅绑定本机HTTP；认证留在私有字段，不接受带凭据/路径的地址。
   *
   * @param {object} options 本机endpoint、projectId、publicKey和secretKey
   */
  constructor({ endpoint, projectId, publicKey, secretKey }) {
    let url;
    try {
      url = new URL(endpoint);
    } catch {
      throw new Error("LANGFUSE_TRACE_PROTOCOL_CONFIG_INVALID");
    }
    if (
      url.protocol !== "http:" ||
      url.hostname !== "127.0.0.1" ||
      url.pathname !== "/" ||
      url.search ||
      url.hash ||
      url.username ||
      url.password ||
      !/^[a-zA-Z0-9_-]{1,128}$/.test(projectId ?? "") ||
      !publicKey ||
      !secretKey
    ) {
      throw new Error("LANGFUSE_TRACE_PROTOCOL_CONFIG_INVALID");
    }
    this.#origin = url.origin;
    this.#project = projectId;
    this.#authentication = `Basic ${Buffer.from(`${publicKey}:${secretKey}`).toString("base64")}`;
  }

  /**
   * 避免序列化时包含认证和平台响应。
   *
   * @returns {object} 固定脱敏标记
   */
  toJSON() {
    return { type: "TraceScoreProtocolClient", authentication: "redacted" };
  }

  /**
   * 查询Key所属项目；不匹配时不写入。
   *
   * @returns {Promise<void>} 项目验证结果
   */
  async verifyProject() {
    const { body } = await this.#request("/api/public/projects", "GET");
    if (!Array.isArray(body.data) || body.data.length !== 1 || body.data[0].id !== this.#project) {
      throw new Error("LANGFUSE_TRACE_PROTOCOL_PROJECT_MISMATCH");
    }
    this.#verified = true;
  }

  /**
   * 仅投递一次无正文轨迹；HTTP成功仍只记ACCEPTED，必须另行查询。
   *
   * @param {object} scope 有效协议范围
   * @returns {Promise<string>} ACCEPTED；未知或部分失败抛错且不重发
   */
  async submitTrace(scope) {
    validateDatasetScope(scope, true);
    if (!this.#verified) await this.verifyProject();
    validateDatasetScope(scope, true);
    if (this.#runs.has(scope.runKey)) throw new Error("LANGFUSE_TRACE_PROTOCOL_REPLAY_BLOCKED");
    const state = {
      startedAt: scope.startedAt,
      expiresAt: scope.expiresAt,
      trace: "UNKNOWN",
      score: "NOT_SENT",
    };
    // 发送前记未知，异常路径也不能在同实例中再次写入。
    this.#runs.set(scope.runKey, state);
    const { status, body } = await this.#request(
      "/api/public/otel/v1/traces",
      "POST",
      protocolTracePayload(scope),
    );
    if (
      status !== 200 ||
      Object.keys(body).some((key) => key !== "partialSuccess") ||
      (Object.hasOwn(body, "partialSuccess") &&
        (body.partialSuccess === null ||
          typeof body.partialSuccess !== "object" ||
          Array.isArray(body.partialSuccess) ||
          Object.keys(body.partialSuccess).some(
            (key) => !["rejectedSpans", "errorMessage"].includes(key),
          ) ||
          ![undefined, 0, "0"].includes(body.partialSuccess.rejectedSpans) ||
          ![undefined, ""].includes(body.partialSuccess.errorMessage)))
    ) {
      throw new Error("LANGFUSE_TRACE_PROTOCOL_PARTIAL_OR_UNKNOWN");
    }
    state.trace = "ACCEPTED";
    return state.trace;
  }

  /**
   * 发送单条技术分数，不使用人工评分来源；207、未知结果和重复调用均不重发。
   *
   * @param {object} scope 已投递轨迹的有效范围
   * @returns {Promise<string>} ACCEPTED不代表分数已落库
   */
  async submitScore(scope) {
    const state = this.#state(scope, true);
    if (state.trace !== "ACCEPTED" || state.score !== "NOT_SENT") {
      throw new Error("LANGFUSE_TRACE_PROTOCOL_REPLAY_BLOCKED");
    }
    state.score = "UNKNOWN";
    const ids = traceProtocolIdentity(scope);
    const { status, body } = await this.#request("/api/public/scores", "POST", [
      {
        id: ids.score,
        traceId: ids.trace,
        name: SCORE_NAME,
        value: 1,
        dataType: "NUMERIC",
        source: "API",
        timestamp: scope.startedAt,
        environment: ENVIRONMENT,
        metadata: {
          namespace: NAMESPACE,
          run_key: scope.runKey,
          original_started_at: scope.startedAt,
          expires_at: scope.expiresAt,
        },
      },
    ]);
    if (status !== 202 || body.message !== "Accepted" || Object.keys(body).length !== 1) {
      throw new Error("LANGFUSE_TRACE_PROTOCOL_PARTIAL_OR_UNKNOWN");
    }
    state.score = "ACCEPTED";
    return state.score;
  }

  /**
   * 单次只读复查两个节点和分数；未落库返回PENDING，不轮询/重放模型。
   *
   * @param {object} scope 原始有效范围
   * @returns {Promise<string>} CONFIRMED或PENDING；错项目/归属/父节点拒绝
   */
  async confirm(scope) {
    const state = this.#state(scope, true);
    if (state.trace !== "ACCEPTED" || state.score !== "ACCEPTED") {
      throw new Error("LANGFUSE_TRACE_PROTOCOL_NOT_ACCEPTED");
    }
    const ids = traceProtocolIdentity(scope);
    const { body: observations } = await this.#request(
      `/api/public/v2/observations?${new URLSearchParams({
        traceId: ids.trace,
        limit: "3",
        fields: "core,basic,metadata",
        expandMetadata: "namespace,run_key,original_started_at,expires_at",
      })}`,
      "GET",
    );
    validateDatasetScope(scope, true);
    const { body: scores } = await this.#request(
      `/api/public/v3/scores?${new URLSearchParams({
        id: ids.score,
        traceId: ids.trace,
        limit: "2",
        fields: "details,subject",
      })}`,
      "GET",
    );
    if (
      !Array.isArray(observations.data) ||
      !Array.isArray(scores.data) ||
      observations.meta?.cursor ||
      observations.meta?.nextCursor ||
      scores.meta?.cursor ||
      scores.meta?.nextCursor ||
      observations.data.length > 2 ||
      scores.data.length > 1
    ) {
      throw new Error("LANGFUSE_TRACE_PROTOCOL_CONFIRM_MISMATCH");
    }
    const seen = new Set();
    for (const row of observations.data) {
      if (row == null || typeof row !== "object" || Array.isArray(row)) {
        throw new Error("LANGFUSE_TRACE_PROTOCOL_CONFIRM_MISMATCH");
      }
      const root = row.id === ids.root;
      if (
        seen.has(row.id) ||
        ![ids.root, ids.child].includes(row.id) ||
        row.traceId !== ids.trace ||
        row.projectId !== this.#project ||
        row.type !== "SPAN" ||
        row.environment !== ENVIRONMENT ||
        row.name !== (root ? "protocol.root" : "protocol.child") ||
        (root
          ? row.parentObservationId != null && row.parentObservationId !== ""
          : row.parentObservationId !== ids.root) ||
        !this.#owns(row.metadata, scope)
      ) {
        throw new Error("LANGFUSE_TRACE_PROTOCOL_CONFIRM_MISMATCH");
      }
      seen.add(row.id);
    }
    for (const row of scores.data) {
      if (
        row == null ||
        typeof row !== "object" ||
        Array.isArray(row) ||
        row.id !== ids.score ||
        row.projectId !== this.#project ||
        row.name !== SCORE_NAME ||
        row.value !== 1 ||
        row.dataType !== "NUMERIC" ||
        row.source !== "API" ||
        row.environment !== ENVIRONMENT ||
        Date.parse(row.timestamp) !== Date.parse(scope.startedAt) ||
        row.subject?.kind !== "trace" ||
        row.subject?.id !== ids.trace ||
        !this.#owns(row.metadata, scope)
      ) {
        throw new Error("LANGFUSE_TRACE_PROTOCOL_CONFIRM_MISMATCH");
      }
    }
    validateDatasetScope(scope, true);
    return seen.size === 2 && scores.data.length === 1 ? "CONFIRMED" : "PENDING";
  }

  /**
   * 校验实例登记的原始期限；不能用相同ID修改时间来延长访问。
   *
   * @param {object} scope 原始范围
   * @param {boolean} active 是否要求未到期
   * @returns {object} 当前实例状态，尚未持久化
   */
  #state(scope, active) {
    validateDatasetScope(scope, active);
    const state = this.#runs.get(scope.runKey);
    if (!state || state.startedAt !== scope.startedAt || state.expiresAt !== scope.expiresAt) {
      throw new Error("LANGFUSE_TRACE_PROTOCOL_SCOPE_MISMATCH");
    }
    return state;
  }

  /**
   * 仅允许固定四项技术元数据，防止把其他来源的响应当作已确认。
   *
   * @param {object} metadata 平台返回的对象
   * @param {object} scope 原始范围
   * @returns {boolean} 归属和期限是否一致
   */
  #owns(metadata, scope) {
    return (
      metadata != null &&
      Object.keys(metadata).length === 4 &&
      metadata.namespace === NAMESPACE &&
      metadata.run_key === scope.runKey &&
      metadata.original_started_at === scope.startedAt &&
      metadata.expires_at === scope.expiresAt
    );
  }

  /**
   * 最多40次本机HTTP、每次5秒/128KiB；不跟重定向，不自动重试，不回显错误正文。
   *
   * @param {string} path 类内固定路径
   * @param {string} method GET或POST
   * @param {object|undefined} payload 协议数据
   * @returns {Promise<object>} HTTP状态与JSON，错误固定脱敏
   */
  async #request(path, method, payload) {
    if (this.#requests++ >= 40) throw new Error("LANGFUSE_TRACE_PROTOCOL_REQUEST_LIMIT");
    try {
      const response = await fetch(`${this.#origin}${path}`, {
        method,
        redirect: "error",
        signal: AbortSignal.timeout(5000),
        headers: {
          Authorization: this.#authentication,
          "Content-Type": "application/json",
          "x-langfuse-ingestion-version": "4",
        },
        body: payload == null ? undefined : JSON.stringify(payload),
      });
      if (!response.ok) {
        await response.body?.cancel();
        throw new Error("STATUS");
      }
      const chunks = [];
      let size = 0;
      if (response.body)
        for await (const bytes of response.body) {
          size += bytes.length;
          if (size > 128 * 1024) throw new Error("SIZE");
          chunks.push(bytes);
        }
      const body = JSON.parse(Buffer.concat(chunks, size).toString("utf8"));
      if (!body || typeof body !== "object" || Array.isArray(body)) throw new Error("BODY");
      return { status: response.status, body };
    } catch {
      throw new Error("LANGFUSE_TRACE_PROTOCOL_REQUEST_UNCONFIRMED");
    }
  }
}
