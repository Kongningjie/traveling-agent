import assert from "node:assert/strict";
import http from "node:http";
import { once } from "node:events";
import test from "node:test";
import { createGateway } from "./gateway.mjs";

/**
 * 在回环随机端口监听测试服务。
 * @param {import('node:http').Server} server 测试服务
 * @returns {Promise<string>} 回环地址
 */
async function listen(server) {
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  return `http://127.0.0.1:${server.address().port}`;
}

/**
 * 关闭测试连接，避免keep-alive延长测试退出。
 * @param {import('node:http').Server} server 测试服务
 * @returns {void} 立即关闭
 */
function close(server) {
  server.closeAllConnections();
  server.close();
}

/** 每次查询都生成短期测试资格，不代表真实PG治理通过。 */
function yes() {
  return Promise.resolve({ allowed: true, notAfter: Date.now() + 1000 });
}

test("控制服务失败或资格过期时不访问上游", async () => {
  let calls = 0;
  const upstream = http.createServer((req, res) => {
    calls++;
    res.end("synthetic-private");
  });
  const origin = await listen(upstream);
  for (const authorize of [
    async () => {
      throw new Error("private-diagnostic");
    },
    async () => ({ allowed: true, notAfter: Date.now() - 1 }),
  ]) {
    const gateway = createGateway({ upstream: origin, authorize });
    try {
      const result = await fetch(await listen(gateway));
      assert.equal(result.status, 503);
      assert.match(result.headers.get("content-type"), /^text\/plain/);
      assert.equal(await result.text(), "LANGFUSE_ACCESS_CLOSED");
    } finally {
      close(gateway);
    }
  }
  assert.equal(calls, 0);
  close(upstream);
});

test("上游处理中撤销后丢弃已返回正文，不因HTTP成功放行", async () => {
  let revoked = false;
  const upstream = http.createServer((req, res) => {
    revoked = true;
    res.end("synthetic-private");
  });
  const gateway = createGateway({
    upstream: await listen(upstream),
    authorize: async () => ({ allowed: !revoked, notAfter: Date.now() + 1000 }),
  });
  try {
    const result = await fetch(await listen(gateway));
    assert.equal(result.status, 503);
    assert.equal(await result.text(), "LANGFUSE_ACCESS_CLOSED");
  } finally {
    close(gateway);
    close(upstream);
  }
});

test("保留多个登录Cookie，禁缓存，POST正文只转发至固定上游", async () => {
  let received = "";
  const upstream = http.createServer(async (req, res) => {
    for await (const data of req) received += data;
    assert.equal(req.headers["x-forwarded-host"], "127.0.0.1:3100");
    assert.equal(req.headers["x-evaluation-gate"], undefined);
    res.setHeader("Set-Cookie", ["test_a=synthetic; HttpOnly", "test_b=synthetic; HttpOnly"]);
    res.setHeader("Cache-Control", "public,max-age=3600");
    res.end("synthetic-public");
  });
  const gateway = createGateway({ upstream: await listen(upstream), authorize: yes });
  try {
    const result = await fetch(await listen(gateway), {
      method: "POST",
      body: "synthetic-input",
      headers: { "x-forwarded-host": "untrusted.invalid", "x-evaluation-gate": "not-forwarded" },
    });
    assert.equal(result.status, 200);
    assert.equal(received, "synthetic-input");
    assert.equal(result.headers.get("cache-control"), "no-store");
    assert.equal(result.headers.getSetCookie().length, 2);
    assert.equal(await result.text(), "synthetic-public");
  } finally {
    close(gateway);
    close(upstream);
  }
});

test("首个请求放行后不缓存资格，后续请求重新检查", async () => {
  let access = true;
  let calls = 0;
  const upstream = http.createServer((req, res) => {
    calls++;
    res.end("synthetic");
  });
  const gateway = createGateway({
    upstream: await listen(upstream),
    authorize: async () => ({ allowed: access, notAfter: Date.now() + 1000 }),
  });
  try {
    const url = await listen(gateway);
    assert.equal((await fetch(url)).status, 200);
    access = false;
    assert.equal((await fetch(url)).status, 503);
    assert.equal(calls, 1);
    assert.equal((await fetch(`${url}/_gateway/health`)).status, 200);
    assert.equal(calls, 1);
  } finally {
    close(gateway);
    close(upstream);
  }
});

test("拒绝将协议相对请求解析成其他主机，不跟随上游重定向", async () => {
  let calls = 0;
  const upstream = http.createServer((req, res) => {
    calls++;
    res.writeHead(302, { Location: "http://untrusted.invalid/" }).end();
  });
  const gateway = createGateway({ upstream: await listen(upstream), authorize: yes });
  try {
    const url = await listen(gateway);
    const status = await new Promise((resolve) => {
      http.get(`${url}//untrusted.invalid/`, (res) => {
        res.resume();
        resolve(res.statusCode);
      });
    });
    assert.equal(status, 503);
    assert.equal(calls, 0);
    assert.equal((await fetch(url, { redirect: "manual" })).status, 302);
    assert.equal(calls, 1);
  } finally {
    close(gateway);
    close(upstream);
  }
});

test("请求超限不发送上游，响应超限不返回正文", async () => {
  let calls = 0;
  const upstream = http.createServer((req, res) => {
    calls++;
    res.end(Buffer.alloc(17 * 1024 * 1024, "x"));
  });
  const gateway = createGateway({ upstream: await listen(upstream), authorize: yes });
  try {
    const url = await listen(gateway);
    const largeRequest = await fetch(url, { method: "POST", body: Buffer.alloc(9 * 1024 * 1024) });
    assert.equal(largeRequest.status, 413);
    assert.equal(calls, 0);
    const largeResponse = await fetch(url);
    assert.equal(largeResponse.status, 502);
    assert.equal(await largeResponse.text(), "LANGFUSE_GATE_UNAVAILABLE");
    assert.equal(calls, 1);
  } finally {
    close(gateway);
    close(upstream);
  }
});

test("最多四个并行代理请求和八个等待者，健康查询不消耗上游配额", async () => {
  const pending = [];
  let releaseAll = false;
  let receivedAll;
  const all = new Promise((resolve) => {
    receivedAll = resolve;
  });
  const upstream = http.createServer((req, res) => {
    if (releaseAll) {
      res.end("synthetic");
      return;
    }
    pending.push(res);
    if (pending.length === 4) receivedAll();
  });
  const gateway = createGateway({ upstream: await listen(upstream), authorize: yes });
  const url = await listen(gateway);
  try {
    let rejected = 0;
    let queueFull;
    const full = new Promise((resolve) => {
      queueFull = resolve;
    });
    const requests = Array.from({ length: 20 }, () =>
      fetch(url).then((response) => {
        if (response.status === 429 && ++rejected === 8) queueFull();
        return response;
      }),
    );
    await Promise.race([
      all,
      new Promise((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error("UPSTREAM_REQUESTS_MISSING")), 3000);
        timeout.unref();
      }),
    ]);
    await Promise.race([
      full,
      new Promise((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error("QUEUE_LIMIT_NOT_ENFORCED")), 3000);
        timeout.unref();
      }),
    ]);
    assert.equal((await fetch(`${url}/_gateway/health`)).status, 200);
    assert.equal(pending.length, 4);
    releaseAll = true;
    for (const response of pending) response.end("synthetic");
    const responses = await Promise.all(requests);
    assert.equal(responses.filter((response) => response.status === 200).length, 12);
    assert.equal(responses.filter((response) => response.status === 429).length, 8);
  } finally {
    for (const response of pending) response.end();
    close(gateway);
    close(upstream);
  }
});
