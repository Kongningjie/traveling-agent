import {
  DATASET_NAMESPACE,
  PROTOCOL_DESCRIPTION,
  datasetScope,
  ownsDataset,
  validateDatasetScope,
} from "./managed-datasets.mjs";

/** 数据集PG存储适配；只清理无正文协议探针，媒体/实验/源轨迹另有存储时拒绝。 */
export class ProtocolDatasetCleaner {
  #database;
  #project;

  /**
   * 绑定固定镜像内的原生Prisma客户端，不导出数据库URL或密码。
   * @param {object} database 原生Prisma客户端
   * @param {string} projectId 固定本机评测项目
   */
  constructor(database, projectId) {
    if (!database || !/^[a-zA-Z0-9_-]{1,128}$/.test(projectId ?? "")) {
      throw new Error("LANGFUSE_DATASET_CLEANER_CONFIG_INVALID");
    }
    this.#database = database;
    this.#project = projectId;
  }

  /**
   * 检查固定版本的级联约束；第三方表结构变化时不继续硬删除。
   * @returns {Promise<void>} 缺少预期级联时拒绝
   */
  async verifySchema() {
    const rows = await this.#database.$queryRaw`
      SELECT c.relname::text AS relname,con.confdeltype::text AS confdeltype FROM pg_constraint con JOIN pg_class c ON c.oid=con.conrelid
      WHERE con.contype='f' AND con.confrelid='public.datasets'::regclass`;
    if (
      rows.length !== 2 ||
      rows.some((row) => row.confdeltype !== "c") ||
      new Set(rows.map((row) => row.relname)).size !== 2 ||
      rows.some((row) => !["dataset_items", "dataset_runs"].includes(row.relname))
    ) {
      throw new Error("LANGFUSE_DATASET_SCHEMA_UNSUPPORTED");
    }
  }

  /**
   * 找回中途退出留下的本工具探针；最多20项，超出时不截断后冒充全部处理。
   * @returns {Promise<object[]>} 仅含原始范围，不包含评测正文
   */
  async pending() {
    const rows = await this.#database.$queryRaw`
      SELECT name,metadata FROM datasets WHERE project_id=${this.#project}
        AND metadata->>'namespace'=${DATASET_NAMESPACE} AND metadata->>'purpose'='PROTOCOL_PROBE'
      ORDER BY created_at,id LIMIT 21`;
    if (rows.length > 20) throw new Error("LANGFUSE_DATASET_CLEANUP_CAPACITY");
    return rows.map((row) => {
      const scope = datasetScope(row.metadata.run_key, row.metadata.original_started_at);
      if (row.name !== scope.name || !ownsDataset(row.metadata, scope)) {
        throw new Error("LANGFUSE_DATASET_OWNERSHIP_MISMATCH");
      }
      return scope;
    });
  }

  /**
   * 查询包括已删除/历史版本的实际记录数，不能用当前API不可见代替。
   * @param {object} scope 本工具范围
   * @returns {Promise<number>} 全部版本数
   */
  async versions(scope) {
    validateDatasetScope(scope);
    const rows = await this.#database.$queryRaw`
      SELECT COUNT(*)::integer AS count FROM dataset_items i JOIN datasets d
        ON d.id=i.dataset_id AND d.project_id=i.project_id
      WHERE d.project_id=${this.#project} AND d.name=${scope.name}`;
    return rows[0].count;
  }

  /**
   * 在同一事务锁定精确数据集、验证空正文及无外部引用，再级联清理所有条目版本。
   * @param {object} scope 原始范围，可已到期
   * @returns {Promise<object>} PG图删除数量；不代表S3/CH/缓存/备份已清理
   */
  async purge(scope) {
    validateDatasetScope(scope);
    await this.verifySchema();
    return this.#database.$transaction(
      async (tx) => {
        const roots = await tx.$queryRaw`
        SELECT id,metadata,
          (description IS DISTINCT FROM ${PROTOCOL_DESCRIPTION}
            OR COALESCE(remote_experiment_url,'')<>''
            OR COALESCE(remote_experiment_display_secret_key,'')<>''
            OR COALESCE(remote_experiment_secret_key,'')<>''
            OR (remote_experiment_payload IS NOT NULL AND remote_experiment_payload NOT IN ('null'::jsonb,'{}'::jsonb))
            OR (remote_experiment_display_headers IS NOT NULL AND remote_experiment_display_headers NOT IN ('null'::jsonb,'{}'::jsonb,'[]'::jsonb))
            OR (remote_experiment_request_headers IS NOT NULL AND remote_experiment_request_headers NOT IN ('null'::jsonb,'{}'::jsonb,'[]'::jsonb))
            OR (input_schema IS NOT NULL AND input_schema::jsonb<>'null'::jsonb)
            OR (expected_output_schema IS NOT NULL AND expected_output_schema::jsonb<>'null'::jsonb)) AS unsafe
        FROM datasets WHERE project_id=${this.#project} AND name=${scope.name} FOR UPDATE`;
        if (roots.length === 0) return { datasets: 0, versions: 0 };
        if (
          roots.length !== 1 ||
          !ownsDataset(roots[0].metadata, scope) ||
          Object.keys(roots[0].metadata).length !== 5
        ) {
          throw new Error("LANGFUSE_DATASET_OWNERSHIP_MISMATCH");
        }
        if (roots[0].unsafe) throw new Error("LANGFUSE_DATASET_CLEANUP_REQUIRES_OTHER_STORES");
        const id = roots[0].id;
        const counts = await tx.$queryRaw`
        SELECT COUNT(*)::integer AS versions,
          COUNT(*) FILTER(WHERE source_trace_id IS NOT NULL OR source_observation_id IS NOT NULL
            OR id NOT IN (${scope.name + "-P01"},${scope.name + "-P02"},${scope.name + "-R01"},${scope.name + "-R02"},${scope.name + "-R03"})
            OR NOT (
              COALESCE(input IN ('{"__ta_protocol_revision":1}'::jsonb,'{"__ta_protocol_revision":2}'::jsonb)
                AND expected_output IS NULL AND jsonb_typeof(metadata)='object'
                AND metadata->>'namespace'=${DATASET_NAMESPACE} AND metadata->>'run_key'=${scope.runKey}
                AND metadata->>'purpose'='PROTOCOL_PROBE'
                AND metadata->>'original_started_at'=${scope.startedAt} AND metadata->>'expires_at'=${scope.expiresAt}
                AND id=${scope.name + "-"}||(metadata->>'case_id')
                AND metadata->>'protocol_revision'=input->>'__ta_protocol_revision'
                AND (metadata-ARRAY['namespace','purpose','run_key','original_started_at','expires_at','case_id','protocol_revision'])='{}'::jsonb,false)
              OR COALESCE(is_deleted AND input IS NULL AND expected_output IS NULL AND metadata IS NULL,false)
            ))::integer AS unsafe
        FROM dataset_items WHERE project_id=${this.#project} AND dataset_id=${id}`;
        const references = await tx.$queryRaw`
        SELECT (SELECT COUNT(*) FROM dataset_runs WHERE project_id=${this.#project} AND dataset_id=${id})
          +(SELECT COUNT(*) FROM dataset_item_media WHERE project_id=${this.#project} AND dataset_id=${id}) AS count`;
        if (counts[0].unsafe !== 0 || Number(references[0].count) !== 0) {
          throw new Error("LANGFUSE_DATASET_CLEANUP_REQUIRES_OTHER_STORES");
        }
        const removed = await tx.$executeRaw`
        DELETE FROM datasets WHERE project_id=${this.#project} AND id=${id} AND name=${scope.name}
          AND metadata->>'namespace'=${DATASET_NAMESPACE} AND metadata->>'run_key'=${scope.runKey}`;
        const left = await tx.$queryRaw`
        SELECT COUNT(*)::integer AS count FROM dataset_items WHERE project_id=${this.#project} AND dataset_id=${id}`;
        if (removed !== 1 || left[0].count !== 0)
          throw new Error("LANGFUSE_DATASET_CLEANUP_UNCONFIRMED");
        return { datasets: removed, versions: counts[0].versions };
      },
      { maxWait: 3000, timeout: 10000 },
    );
  }
}
