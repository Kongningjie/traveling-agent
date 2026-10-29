import { pathToFileURL } from "node:url";

/**
 * 从固定缩进的本机OpenAPI取单个区块；格式改变时拒绝，不猜接口。
 *
 * @param {string} source 原生OpenAPI YAML
 * @param {string} key 路径或schema名
 * @param {number} indent 固定缩进
 * @returns {string} 区块或空串
 */
function section(source, key, indent) {
  const prefix = " ".repeat(indent);
  const marker = `\n${prefix}${key}:\n`;
  const index = `\n${source}`.indexOf(marker);
  if (index < 0) return "";
  const rest = `\n${source}`.slice(index + marker.length);
  const boundary = rest.search(new RegExp(`\\n${prefix}\\S[^\\n]*:\\n`));
  return boundary < 0 ? rest : `${rest.slice(0, boundary)}\n`;
}

/**
 * 只检查本适配器依赖的路径、参数和请求类型；不是执行写入的证据。
 *
 * @param {string} source 固定镜像的OpenAPI
 * @returns {void} 不满足协议时拒绝
 */
export function verifyTraceScoreOpenApi(source) {
  if (typeof source !== "string") throw new Error("LANGFUSE_TRACE_SCHEMA_UNSUPPORTED");
  const trace = section(source, "/api/public/otel/v1/traces", 2);
  const score = section(source, "/api/public/scores", 2);
  const read = section(source, "/api/public/v3/scores", 2);
  const nodes = section(source, "/api/public/v2/observations", 2);
  const request = section(source, "CreateScoresRequest", 4);
  const item = section(source, "CreateScoreRequest", 4);
  if (
    !trace.includes("    post:") ||
    !trace.includes("application/json:") ||
    !trace.includes("x-langfuse-ingestion-version:") ||
    !trace.includes("resourceSpans:") ||
    !score.includes("    post:") ||
    !score.includes("CreateScoresRequest") ||
    !score.includes("202") ||
    !score.includes("207") ||
    !request.includes("type: array") ||
    !request.includes("CreateScoreRequest") ||
    !["traceId:", "timestamp:", "source:", "metadata:", "dataType:"].every((field) =>
      item.includes(`        ${field}`),
    ) ||
    !read.includes("    get:") ||
    !["id", "traceId", "fields", "limit"].every((name) => read.includes(`- name: ${name}\n`)) ||
    !nodes.includes("    get:") ||
    !["traceId", "fields", "expandMetadata"].every((name) => nodes.includes(`- name: ${name}\n`))
  ) {
    throw new Error("LANGFUSE_TRACE_SCHEMA_UNSUPPORTED");
  }
}

/**
 * 有界读取本机协议/schema；拒绝重定向和原生错误正文，不输出配置。
 *
 * @param {string} url 类内固定本机地址
 * @param {object} options 只读GET或ClickHouse SELECT请求
 * @returns {Promise<string>} 最多2MiB的协议文本
 */
async function readBounded(url, options = {}) {
  try {
    const response = await fetch(url, {
      ...options,
      redirect: "error",
      signal: AbortSignal.timeout(5000),
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
        if (size > 2 * 1024 * 1024) throw new Error("SIZE");
        chunks.push(bytes);
      }
    return Buffer.concat(chunks, size).toString("utf8");
  } catch {
    throw new Error("LANGFUSE_TRACE_SCHEMA_READ_UNCONFIRMED");
  }
}

/**
 * 只读核对固定镜像的OpenAPI及CH技术列；不实例化发布客户端，不写入任何分数/轨迹。
 *
 * @returns {Promise<void>} 两组只读检查结果
 */
async function main() {
  const source = await readBounded("http://127.0.0.1:3000/api/openapi.yaml");
  verifyTraceScoreOpenApi(source);
  console.log("TRACE_SCORE_NATIVE_OPENAPI_READ_ONLY_PASS");
  if (
    process.env.CLICKHOUSE_URL !== "http://clickhouse:8123" ||
    !process.env.CLICKHOUSE_USER ||
    !process.env.CLICKHOUSE_PASSWORD
  ) {
    throw new Error("LANGFUSE_TRACE_SCHEMA_CONFIG_INVALID");
  }
  const authentication = `Basic ${Buffer.from(`${process.env.CLICKHOUSE_USER}:${process.env.CLICKHOUSE_PASSWORD}`).toString("base64")}`;
  const result = JSON.parse(
    await readBounded("http://clickhouse:8123", {
      method: "POST",
      headers: { Authorization: authentication },
      body: "SELECT table,name FROM system.columns WHERE database=currentDatabase() AND table IN ('traces','observations','scores','blob_storage_file_log') FORMAT JSON",
    }),
  );
  const required = {
    traces: ["id", "project_id", "metadata", "is_deleted"],
    observations: [
      "id",
      "trace_id",
      "parent_observation_id",
      "project_id",
      "metadata",
      "is_deleted",
    ],
    scores: [
      "id",
      "trace_id",
      "project_id",
      "timestamp",
      "source",
      "value",
      "metadata",
      "is_deleted",
    ],
    blob_storage_file_log: ["entity_id", "project_id", "bucket_name", "bucket_path", "is_deleted"],
  };
  if (
    !Array.isArray(result.data) ||
    !Object.entries(required).every(([table, columns]) =>
      columns.every((name) => result.data.some((row) => row.table === table && row.name === name)),
    )
  ) {
    throw new Error("LANGFUSE_TRACE_SCHEMA_UNSUPPORTED");
  }
  console.log("TRACE_SCORE_NATIVE_STORAGE_SCHEMA_READ_ONLY_PASS");
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(() => {
    console.error("LANGFUSE_TRACE_SCORE_CONTRACT_FAILED");
    process.exitCode = 1;
  });
}
