import http from "node:http";
import { pathToFileURL } from "node:url";

const HOP_HEADERS = new Set([
  "connection",
  "keep-alive",
  "proxy-authenticate",
  "proxy-authorization",
  "te",
  "trailer",
  "transfer-encoding",
  "upgrade",
  "host",
  "content-length",
]);
const MAX_BODY = 8 * 1024 * 1024;
const MAX_RESPONSE = 16 * 1024 * 1024;

/**
 * 读取有界正文，超限即中断，不记录内容。
 * @param {AsyncIterable<Uint8Array>} stream 请求或响应流
 * @param {number} limit 最大字节数
 * @returns {Promise<Buffer>} 完整字节
 */
async function boundedBody(stream, limit) {
  const chunks = [];
  let size = 0;
  for await (const chunk of stream) {
    size += chunk.length;
    if (size > limit) throw new Error("BODY_LIMIT");
    chunks.push(chunk);
  }
  return Buffer.concat(chunks, size);
}

/**
 * 只按新查询和当前时间判断资格，不能缓存上一请求的允许结果。
 * @param {() => Promise<{allowed: boolean, notAfter: number}>} authorize 控制查询
 * @returns {Promise<boolean>} 当前是否允许；异常拒绝
 */
async function allowed(authorize) {
  try {
    const result = await authorize();
    return (
      result?.allowed === true && Number.isFinite(result.notAfter) && Date.now() < result.notAfter
    );
  } catch {
    return false;
  }
}

/**
 * 创建整站访问闸门；先检查、缓冲有界响应、再检查，不把撤销后的上游正文发给浏览器。
 * @param {{upstream: string, authorize: () => Promise<{allowed: boolean, notAfter: number}>}} options 可信装配
 * @returns {import('node:http').Server} 尚未监听的服务
 */
export function createGateway({ upstream, authorize }) {
  const origin = new URL(upstream);
  let active = 0;
  const waiting = [];
  /**
   * 最多四个转发，八个短等待；浏览器并行加载静态资源时不因第五个请求立即失败。
   * @returns {Promise<boolean>} 是否取得名额
   */
  function acquire() {
    if (active < 4) {
      active++;
      return Promise.resolve(true);
    }
    if (waiting.length >= 8) return Promise.resolve(false);
    return new Promise((resolve) => {
      const entry = { resolve, timer: null };
      entry.timer = setTimeout(() => {
        const index = waiting.indexOf(entry);
        if (index >= 0) waiting.splice(index, 1);
        resolve(false);
      }, 5000);
      waiting.push(entry);
    });
  }
  /** 把已有名额转给最早等待者；无人等待时才减少占用。 */
  function release() {
    const entry = waiting.shift();
    if (entry) {
      clearTimeout(entry.timer);
      entry.resolve(true);
    } else active--;
  }
  const server = http.createServer(async (request, response) => {
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Content-Type", "text/plain; charset=utf-8");
    if (request.method === "GET" && request.url === "/_gateway/health") {
      response.writeHead(200).end("GATEWAY_ALIVE");
      return;
    }
    if (!(await acquire())) {
      response.writeHead(429).end("LANGFUSE_GATE_BUSY");
      return;
    }
    let readingRequest = true;
    try {
      const target = new URL(request.url, origin);
      if (target.origin !== origin.origin || !(await allowed(authorize))) {
        response.writeHead(503).end("LANGFUSE_ACCESS_CLOSED");
        return;
      }
      const body = await boundedBody(request, MAX_BODY);
      readingRequest = false;
      if (!(await allowed(authorize))) {
        response.writeHead(503).end("LANGFUSE_ACCESS_CLOSED");
        return;
      }
      const headers = new Headers();
      // 去掉调用者指定的逐跳字段与转发身份，避免连接选项或Host造成旁路。
      const connectionHeaders = new Set(
        (request.headers.connection ?? "")
          .toLowerCase()
          .split(",")
          .map((v) => v.trim()),
      );
      for (const [key, value] of Object.entries(request.headers)) {
        if (
          value == null ||
          HOP_HEADERS.has(key) ||
          connectionHeaders.has(key) ||
          key.startsWith("x-forwarded-") ||
          key === "x-evaluation-gate" ||
          key === "if-none-match" ||
          key === "if-modified-since"
        )
          continue;
        headers.set(key, Array.isArray(value) ? value.join(", ") : value);
      }
      headers.set("x-forwarded-host", "127.0.0.1:3100");
      headers.set("x-forwarded-proto", "http");
      const upstreamResponse = await fetch(target, {
        method: request.method,
        headers,
        redirect: "manual",
        body: body.length ? body : undefined,
        signal: AbortSignal.timeout(10_000),
      });
      const payload = upstreamResponse.body
        ? await boundedBody(upstreamResponse.body, MAX_RESPONSE)
        : Buffer.alloc(0);
      if (!(await allowed(authorize))) {
        response.writeHead(503).end("LANGFUSE_ACCESS_CLOSED");
        return;
      }
      for (const [key, value] of upstreamResponse.headers) {
        if (
          HOP_HEADERS.has(key) ||
          ["set-cookie", "content-encoding", "etag", "cache-control", "expires"].includes(key)
        )
          continue;
        response.setHeader(key, value);
      }
      const cookies = upstreamResponse.headers.getSetCookie();
      if (cookies.length) response.setHeader("Set-Cookie", cookies);
      response.setHeader("Cache-Control", "no-store");
      response.setHeader(
        "Content-Type",
        upstreamResponse.headers.get("content-type") ?? "application/octet-stream",
      );
      response.setHeader("Content-Length", payload.length);
      response.writeHead(upstreamResponse.status).end(payload);
    } catch (failure) {
      // 不返回URL、认证头、请求内容或底层异常。
      const oversized = readingRequest && failure?.message === "BODY_LIMIT";
      if (!response.headersSent) response.writeHead(oversized ? 413 : 502);
      response.end(oversized ? "LANGFUSE_GATE_REQUEST_TOO_LARGE" : "LANGFUSE_GATE_UNAVAILABLE");
    } finally {
      release();
    }
  });
  server.requestTimeout = 15_000;
  server.headersTimeout = 10_000;
  server.maxHeadersCount = 100;
  return server;
}

/**
 * 显式选择本机合成展示或原治理策略，不修改PG治理结论。
 *
 * @param {string} mode 固定模式名
 * @param {Function} governed 原治理查询
 * @returns {Function} 本次请求使用的策略，未知模式拒绝
 */
export function accessAuthorizer(mode, governed) {
  if (!["governed", "local-synthetic"].includes(mode))
    throw new Error("LANGFUSE_ACCESS_MODE_INVALID");
  if (typeof governed !== "function") throw new Error("LANGFUSE_ACCESS_MODE_INVALID");
  return mode === "local-synthetic"
    ? async () => ({ allowed: true, notAfter: Date.now() + 1000 })
    : governed;
}

/** 启动固定内部上游；合成展示仍由原生平台认证，不修改PG治理结论。 */
function main() {
  const mode = process.env.LF_ACCESS_MODE ?? "governed";
  const token = process.env.LF_GATE_TOKEN;
  if (!/^[0-9a-f]{64}$/.test(token ?? "")) throw new Error("LANGFUSE_GATE_CONFIG_REQUIRED");
  const server = createGateway({
    upstream: "http://web:3000",
    authorize: accessAuthorizer(mode, async () => {
      const result = await fetch("http://host.docker.internal:8093/access", {
        headers: { "X-Evaluation-Gate": token },
        signal: AbortSignal.timeout(1500),
        redirect: "manual",
      });
      return {
        allowed: result.status === 204,
        notAfter: Number(result.headers.get("X-Access-Not-After")),
      };
    }),
  });
  server.listen(3000, "0.0.0.0");
  process.once("SIGTERM", () => server.close(() => process.exit(0)));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main();
