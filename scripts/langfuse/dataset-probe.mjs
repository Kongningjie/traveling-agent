import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { randomUUID } from "node:crypto";
import { pathToFileURL } from "node:url";
import { datasetScope, ManagedDatasetClient } from "./managed-datasets.mjs";
import { ProtocolDatasetCleaner } from "./dataset-cleaner.mjs";

let stage = "INIT";

/**
 * 在现有事务内制造不安全状态并验证拒删，随后回滚；不提交假正文或损坏标记。
 * @param {object} database 原生Prisma
 * @param {string} project 固定项目
 * @param {object} scope 本例范围
 * @param {string} datasetId 本例数据集
 * @param {string} kind OWNERSHIP、BODY、CONFIG或EXPERIMENT
 * @returns {Promise<void>} 必须拒绝且保留原记录
 */
async function rejectsUnsafe(database, project, scope, datasetId, kind) {
  const sentinel = new Error("PROBE_ROLLBACK");
  try {
    await database.$transaction(async (tx) => {
      if (kind === "OWNERSHIP")
        await tx.$executeRaw`
        UPDATE datasets SET metadata='{"namespace":"foreign-control"}'::jsonb
        WHERE project_id=${project} AND id=${datasetId}`;
      else if (kind === "BODY")
        await tx.$executeRaw`
        UPDATE dataset_items SET input='{"protocol_control":true}'::jsonb
        WHERE project_id=${project} AND dataset_id=${datasetId}`;
      else if (kind === "CONFIG")
        await tx.$executeRaw`
        UPDATE datasets SET description='Rollback-only changed description'
        WHERE project_id=${project} AND id=${datasetId}`;
      else
        await tx.$executeRaw`
        INSERT INTO dataset_runs(id,name,dataset_id,project_id,created_at,updated_at,metadata,description)
        VALUES(${"ta-protocol-run-" + scope.runKey},'Protocol guard only',${datasetId},${project},
          CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'{}'::jsonb,'Rollback-only reference guard')`;
      const nested = {
        $queryRaw: (strings, ...values) => tx.$queryRaw(strings, ...values),
        $transaction: (work) => work(tx),
      };
      const cleaner = new ProtocolDatasetCleaner(nested, project);
      await assert.rejects(cleaner.purge(scope), {
        message:
          kind === "OWNERSHIP"
            ? "LANGFUSE_DATASET_OWNERSHIP_MISMATCH"
            : "LANGFUSE_DATASET_CLEANUP_REQUIRES_OTHER_STORES",
      });
      const left =
        await tx.$queryRaw`SELECT COUNT(*)::integer AS count FROM datasets WHERE project_id=${project} AND id=${datasetId}`;
      assert.equal(left[0].count, 1);
      throw sentinel;
    });
  } catch (failure) {
    if (failure !== sentinel) throw failure;
  }
}

/**
 * 仅在固定容器内部验证空数据集协议与PG版本清理，不开启入口、不调用模型或其他供应商。
 * @returns {Promise<void>} 输出固定检查状态，不输出配置、原始响应或技术ID
 */
async function main() {
  const require = createRequire("/app/web/package.json");
  const { PrismaClient } = require("@prisma/client");
  const database = new PrismaClient({ log: [] });
  const project = "traveling-agent-evaluation";
  const cleaner = new ProtocolDatasetCleaner(database, project);
  const client = new ManagedDatasetClient({
    endpoint: "http://127.0.0.1:3000/",
    projectId: project,
    publicKey: process.env.LANGFUSE_INIT_PROJECT_PUBLIC_KEY,
    secretKey: process.env.LANGFUSE_INIT_PROJECT_SECRET_KEY,
  });
  let scope;
  try {
    await cleaner.verifySchema();
    stage = "RECOVERY";
    // 先找回本工具上次中断留下的空探针；不是重放模型或正式评测。
    for (const pending of await cleaner.pending()) await cleaner.purge(pending);
    console.log("DATASET_SCHEMA_AND_PENDING_RECOVERY_PASS");
    scope = datasetScope(randomUUID().replaceAll("-", ""), new Date().toISOString());
    stage = "DATASET";
    const id = await client.ensure(scope);
    assert.equal(await client.ensure(scope), id);
    for (const caseId of ["P01", "P02", "R01", "R02", "R03"]) {
      await client.putItem(scope, caseId, 1);
      assert.equal((await client.getItem(scope, caseId)).revision, 1);
    }
    console.log("DATASET_CREATE_REUSE_AND_FIVE_METADATA_ITEMS_PASS");
    stage = "HISTORY";
    await client.putItem(scope, "P01", 2);
    assert.equal((await client.getItem(scope, "P01")).revision, 2);
    const beforeDelete = await cleaner.versions(scope);
    assert.ok(beforeDelete >= 6);
    await client.removeItem(scope, "P01");
    assert.ok((await cleaner.versions(scope)) >= beforeDelete);
    console.log("DATASET_API_DELETE_HISTORY_REMAINS_CONFIRMED");
    stage = "GUARDS";
    await rejectsUnsafe(database, project, scope, id, "OWNERSHIP");
    await rejectsUnsafe(database, project, scope, id, "BODY");
    await rejectsUnsafe(database, project, scope, id, "CONFIG");
    await rejectsUnsafe(database, project, scope, id, "EXPERIMENT");
    console.log("DATASET_OWNERSHIP_BODY_AND_EXPERIMENT_GUARDS_PASS");
    stage = "CLEANUP";
    const removed = await cleaner.purge(scope);
    assert.equal(removed.datasets, 1);
    assert.ok(removed.versions >= 6);
    assert.equal(await cleaner.versions(scope), 0);
    assert.equal(await client.getItem(scope, "P02"), null);
    assert.equal(await client.exists(scope), false);
    assert.equal((await cleaner.purge(scope)).datasets, 0);
    assert.equal((await cleaner.pending()).length, 0);
    console.log("DATASET_PG_ALL_VERSIONS_CLEANED_AND_REPLAY_PASS");
  } finally {
    try {
      if (scope) await cleaner.purge(scope);
    } finally {
      await database.$disconnect();
    }
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((failure) => {
    const code = /^LANGFUSE_[A-Z_]+$/.test(failure?.message ?? "")
      ? failure.message
      : "NATIVE_FAILURE";
    const sql = /^[A-Z0-9]{5}$/.test(failure?.meta?.code ?? "") ? failure.meta.code : "NONE";
    console.error(`LANGFUSE_DATASET_PROBE_FAILED stage=${stage} category=${code} sql=${sql}`);
    process.exitCode = 1;
  });
}
