import assert from "node:assert/strict";
import http from "node:http";
import { once } from "node:events";
import test from "node:test";
import {
  datasetScope,
  datasetMetadata,
  ManagedDatasetClient,
  PROTOCOL_DESCRIPTION,
  validateDatasetScope,
} from "./managed-datasets.mjs";
import { ProtocolDatasetCleaner } from "./dataset-cleaner.mjs";

/**
 * 创建回环HTTP替身，不接真实平台或供应商。
 * @param {(req: object,res: object) => void} handler 受控响应
 * @returns {Promise<object>} 服务、配置和精确关闭函数
 */
async function fixture(handler) {
  const server = http.createServer(handler);
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  return {
    server,
    options: {
      endpoint: `http://127.0.0.1:${server.address().port}/`,
      projectId: "test-project",
      publicKey: "test-public",
      secretKey: "test-secret",
    },
    close: () => {
      server.closeAllConnections();
      server.close();
    },
  };
}

/**
 * 生成仍在有效期内的独立元数据范围。
 * @returns {object} 合成范围
 */
function scope() {
  return datasetScope("a".repeat(32), new Date(Date.now() - 1000).toISOString());
}

/**
 * 返回受控JSON，错误内容也只使用合成标记。
 * @param {object} response HTTP响应
 * @param {number} status 状态码
 * @param {object} body 合成JSON
 * @returns {void} 完成响应
 */
function json(response, status, body) {
  response.writeHead(status, { "Content-Type": "application/json" }).end(JSON.stringify(body));
}

test("原始期限固定30天，过期可清理但不能发布，非法范围和外网地址拒绝", () => {
  const old = datasetScope("a".repeat(32), new Date(Date.now() - 31 * 86400000).toISOString());
  assert.equal(Date.parse(old.expiresAt) - Date.parse(old.startedAt), 30 * 86400000);
  validateDatasetScope(old);
  assert.throws(() => validateDatasetScope(old, true), /SCOPE_INVALID/);
  assert.throws(() => validateDatasetScope({ ...scope(), name: "other-dataset" }), /SCOPE_INVALID/);
  assert.throws(
    () =>
      new ManagedDatasetClient({
        endpoint: "https://example.com/",
        projectId: "test",
        publicKey: "test",
        secretKey: "test",
      }),
    /CONFIG_INVALID/,
  );
});

test("平台缺失级联、出现未知子表或增加外键时拒绝使用清理适配", async () => {
  const base = [
    { relname: "dataset_items", confdeltype: "c" },
    { relname: "dataset_runs", confdeltype: "c" },
  ];
  for (const rows of [
    base.slice(0, 1),
    [base[0], { relname: "dataset_runs", confdeltype: "a" }],
    [base[0], { relname: "unknown_child", confdeltype: "c" }],
    [...base, { relname: "new_child", confdeltype: "c" }],
  ]) {
    const database = { $queryRaw: async () => rows };
    await assert.rejects(
      new ProtocolDatasetCleaner(database, "test-project").verifySchema(),
      /SCHEMA_UNSUPPORTED/,
    );
  }
});

test("项目不匹配时不创建任何数据集", async () => {
  let writes = 0;
  const f = await fixture((req, res) => {
    if (req.method === "POST") writes++;
    json(res, 200, { data: [{ id: "other-project" }] });
  });
  try {
    await assert.rejects(new ManagedDatasetClient(f.options).ensure(scope()), /PROJECT_MISMATCH/);
    assert.equal(writes, 0);
  } finally {
    f.close();
  }
});

test("删除复查是纯读，404后不能调用创建接口复活目标", async () => {
  let writes = 0;
  const f = await fixture((req, res) => {
    if (req.url === "/api/public/projects")
      return json(res, 200, { data: [{ id: "test-project" }] });
    if (req.method !== "GET") writes++;
    json(res, 404, {});
  });
  try {
    assert.equal(await new ManagedDatasetClient(f.options).exists(scope()), false);
    assert.equal(writes, 0);
  } finally {
    f.close();
  }
});

test("创建后只按精确名字复用，同名不同来源拒绝且不覆盖", async () => {
  let dataset = null;
  let writes = 0;
  const s = scope();
  const f = await fixture(async (req, res) => {
    if (req.url === "/api/public/projects")
      return json(res, 200, { data: [{ id: "test-project" }] });
    if (req.method === "GET") return json(res, dataset ? 200 : 404, dataset ?? {});
    writes++;
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    dataset = {
      ...JSON.parse(Buffer.concat(chunks).toString()),
      id: "test-dataset",
      projectId: "test-project",
    };
    json(res, 200, dataset);
  });
  try {
    const client = new ManagedDatasetClient(f.options);
    assert.equal(await client.ensure(s), "test-dataset");
    assert.equal(await client.ensure(s), "test-dataset");
    assert.equal(writes, 1);
    dataset.metadata = { ...dataset.metadata, namespace: "other-owner" };
    await assert.rejects(client.ensure(s), /OWNERSHIP_MISMATCH/);
    assert.equal(writes, 1);
    dataset.metadata = datasetMetadata(s);
    dataset.description = "changed-by-another-workflow";
    await assert.rejects(client.ensure(s), /OWNERSHIP_MISMATCH/);
    assert.equal(writes, 1);
  } finally {
    f.close();
  }
});

test("条目只发布数字协议标记和固定五例ID，查询需匹配精确数据集", async () => {
  const s = scope();
  let item;
  let writes = 0;
  const f = await fixture(async (req, res) => {
    if (req.url === "/api/public/projects")
      return json(res, 200, { data: [{ id: "test-project" }] });
    if (req.url.startsWith("/api/public/v2/datasets/"))
      return json(res, 200, {
        id: "test-dataset",
        name: s.name,
        description: PROTOCOL_DESCRIPTION,
        metadata: datasetMetadata(s),
      });
    if (req.method === "POST") {
      writes++;
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      item = { ...JSON.parse(Buffer.concat(chunks).toString()), datasetId: "test-dataset" };
      return json(res, 200, item);
    }
    json(res, item ? 200 : 404, item ?? {});
  });
  try {
    const client = new ManagedDatasetClient(f.options);
    await assert.rejects(client.putItem(s, "P01", 1), /ITEM_INVALID/);
    await client.ensure(s);
    await client.putItem(s, "P01", 1);
    assert.deepEqual(item.input, { __ta_protocol_revision: 1 });
    assert.equal(item.expectedOutput, null);
    assert.equal((await client.getItem(s, "P01")).revision, 1);
    await assert.rejects(client.putItem(s, "UNREGISTERED", 1), /ITEM_INVALID/);
    item.datasetId = "foreign-dataset";
    await assert.rejects(client.getItem(s, "P01"), /ITEM_MISMATCH/);
    await assert.rejects(client.putItem(s, "P01", 2), /ITEM_MISMATCH/);
    assert.equal(writes, 1);
  } finally {
    f.close();
  }
});

test("GET返回空体或null不能当作404后覆盖未知来源", async () => {
  let body = "";
  let writes = 0;
  const f = await fixture((req, res) => {
    if (req.url === "/api/public/projects")
      return json(res, 200, { data: [{ id: "test-project" }] });
    if (req.method === "POST") writes++;
    res.writeHead(200).end(body);
  });
  try {
    for (const invalid of ["", "null", "[]"]) {
      body = invalid;
      await assert.rejects(
        new ManagedDatasetClient(f.options).ensure(scope()),
        /REQUEST_UNCONFIRMED/,
      );
    }
    assert.equal(writes, 0);
  } finally {
    f.close();
  }
});

test("写入失败没有自动重试，不回传供应商错误正文", async () => {
  const s = scope();
  let writes = 0;
  const f = await fixture((req, res) => {
    if (req.url === "/api/public/projects")
      return json(res, 200, { data: [{ id: "test-project" }] });
    if (req.method === "GET") return json(res, 404, {});
    writes++;
    json(res, 500, { error: "synthetic-private-response" });
  });
  try {
    await assert.rejects(new ManagedDatasetClient(f.options).ensure(s), {
      message: "LANGFUSE_DATASET_REQUEST_UNCONFIRMED",
    });
    assert.equal(writes, 1);
  } finally {
    f.close();
  }
});

test("禁止重定向和超大响应，认证不会被序列化", async () => {
  let mode = "redirect";
  const f = await fixture((req, res) => {
    if (mode === "redirect") res.writeHead(302, { Location: "http://untrusted.invalid/" }).end();
    else res.writeHead(200).end("x".repeat(129 * 1024));
  });
  try {
    const client = new ManagedDatasetClient(f.options);
    assert.equal(JSON.stringify(client).includes(f.options.secretKey), false);
    await assert.rejects(client.verifyProject(), /REQUEST_UNCONFIRMED/);
    mode = "large";
    await assert.rejects(client.verifyProject(), /REQUEST_UNCONFIRMED/);
  } finally {
    f.close();
  }
});

test("每实例最多48次HTTP，不因失败重建调用额度", async () => {
  let calls = 0;
  const f = await fixture((req, res) => {
    calls++;
    json(res, 200, { data: [{ id: "test-project" }] });
  });
  try {
    const client = new ManagedDatasetClient(f.options);
    for (let i = 0; i < 48; i++) await client.verifyProject();
    await assert.rejects(client.verifyProject(), /REQUEST_LIMIT/);
    assert.equal(calls, 48);
    assert.equal(client.requests, 48);
  } finally {
    f.close();
  }
});
