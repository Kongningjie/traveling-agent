/** 固定版本平台的数据集协议适配；当前只允许无正文协议探针，不启用正式评测发布。 */
export const DATASET_NAMESPACE = "traveling-agent-lf3-dataset-v1";
export const PROTOCOL_DESCRIPTION = "Local metadata-only protocol probe";
const DAY = 86_400_000;
const CASES = new Set(["P01", "P02", "R01", "R02", "R03"]);

/**
 * 构造原始开始时间固定的探针范围，不以重试时间重置期限。
 * @param {string} runKey 随机运行的32位小写十六进制
 * @param {string} startedAt 原始UTC时间
 * @returns {Readonly<object>} 仅含技术元数据的范围
 */
export function datasetScope(runKey, startedAt) {
  const start = Date.parse(startedAt);
  if (!/^[a-f0-9]{32}$/.test(runKey) || !Number.isFinite(start)) {
    throw new Error("LANGFUSE_DATASET_SCOPE_INVALID");
  }
  return Object.freeze({
    runKey,
    startedAt: new Date(start).toISOString(),
    expiresAt: new Date(start + 30 * DAY).toISOString(),
    name: `ta-protocol-${runKey}`,
    purpose: "PROTOCOL_PROBE",
  });
}

/**
 * 校验固定命名空间；清理可处理已过期项，发布必须仍有效。
 * @param {object} scope 原始范围
 * @param {boolean} writing 是否准备发布
 * @returns {void} 不符合范围时拒绝，不回显输入
 */
export function validateDatasetScope(scope, writing = false) {
  if (
    !scope ||
    scope.purpose !== "PROTOCOL_PROBE" ||
    !/^[a-f0-9]{32}$/.test(scope.runKey ?? "") ||
    scope.name !== `ta-protocol-${scope.runKey}`
  ) {
    throw new Error("LANGFUSE_DATASET_SCOPE_INVALID");
  }
  const expected = datasetScope(scope.runKey, scope.startedAt);
  if (
    expected.startedAt !== scope.startedAt ||
    expected.expiresAt !== scope.expiresAt ||
    (writing &&
      (Date.now() < Date.parse(scope.startedAt) || Date.now() >= Date.parse(scope.expiresAt)))
  ) {
    throw new Error("LANGFUSE_DATASET_SCOPE_INVALID");
  }
}

/**
 * 生成可验证的托管标记；没有聊天、附件、账号或真实模型结果。
 * @param {object} scope 原始范围
 * @returns {object} 原始时间及范围标记
 */
export function datasetMetadata(scope) {
  validateDatasetScope(scope);
  return {
    namespace: DATASET_NAMESPACE,
    purpose: scope.purpose,
    run_key: scope.runKey,
    original_started_at: scope.startedAt,
    expires_at: scope.expiresAt,
  };
}

/**
 * 校验平台返回的托管标记；不能接管同名的其他数据。
 * @param {object} metadata 平台元数据
 * @param {object} scope 原范围
 * @returns {boolean} 是否属于该托管范围
 */
export function ownsDataset(metadata, scope) {
  const expected = datasetMetadata(scope);
  return (
    metadata != null && Object.entries(expected).every(([key, value]) => metadata[key] === value)
  );
}

/** 数据集REST适配器；密钥只放私有字段，不重试写入、不跟随重定向、不返回平台错误正文。 */
export class ManagedDatasetClient {
  #endpoint;
  #authentication;
  #project;
  #requests = 0;
  #verified = false;
  #datasets = new Map();

  /**
   * 绑定本机平台和项目认证，不允许外网或带凭据URL。
   * @param {{endpoint: string, projectId: string, publicKey: string, secretKey: string}} options 本机配置
   */
  constructor({ endpoint, projectId, publicKey, secretKey }) {
    const url = new URL(endpoint);
    if (
      url.protocol !== "http:" ||
      !["127.0.0.1", "web"].includes(url.hostname) ||
      url.username ||
      url.password ||
      url.search ||
      url.hash ||
      url.pathname !== "/" ||
      !/^[a-zA-Z0-9_-]{1,128}$/.test(projectId ?? "") ||
      !publicKey ||
      !secretKey
    ) {
      throw new Error("LANGFUSE_DATASET_CONFIG_INVALID");
    }
    this.#endpoint = url.origin;
    this.#project = projectId;
    this.#authentication = `Basic ${Buffer.from(`${publicKey}:${secretKey}`).toString("base64")}`;
  }

  /**
   * 返回脱敏类型，避免工具误序列化认证字段。
   * @returns {object} 固定标识
   */
  toJSON() {
    return { type: "ManagedDatasetClient", authentication: "redacted" };
  }

  /**
   * 读取本实例实际HTTP次数，不把重试或缓存当作真实验证。
   * @returns {number} 本机调用数
   */
  get requests() {
    return this.#requests;
  }

  /**
   * 确认API Key对应预期项目；此处只读取项目身份，不返回密钥。
   * @returns {Promise<void>} 项目不匹配即停止
   */
  async verifyProject() {
    const result = await this.#request("/api/public/projects", "GET");
    if (!Array.isArray(result?.data) || !result.data.some((row) => row.id === this.#project)) {
      throw new Error("LANGFUSE_DATASET_PROJECT_MISMATCH");
    }
    this.#verified = true;
  }

  /**
   * 创建或复用相同范围的空数据集，拒绝覆盖同名不同来源。
   * @param {object} scope 原始范围
   * @returns {Promise<string>} 平台数据集技术ID
   */
  async ensure(scope) {
    validateDatasetScope(scope, true);
    if (!this.#verified) await this.verifyProject();
    let result = await this.#request(
      `/api/public/v2/datasets/${scope.name}`,
      "GET",
      undefined,
      true,
    );
    if (!result)
      result = await this.#request("/api/public/v2/datasets", "POST", {
        name: scope.name,
        description: PROTOCOL_DESCRIPTION,
        metadata: datasetMetadata(scope),
      });
    if (
      result?.name !== scope.name ||
      !/^[a-zA-Z0-9_-]{1,128}$/.test(result.id ?? "") ||
      result.description !== PROTOCOL_DESCRIPTION ||
      result.inputSchema != null ||
      result.expectedOutputSchema != null ||
      (result.projectId != null && result.projectId !== this.#project) ||
      !ownsDataset(result.metadata, scope) ||
      Object.keys(result.metadata).length !== 5
    ) {
      throw new Error("LANGFUSE_DATASET_OWNERSHIP_MISMATCH");
    }
    this.#datasets.set(scope.runKey, result.id);
    return result.id;
  }

  /**
   * 精确查询托管数据集是否还存在；不会像ensure一样创建，用于删除后的API复查。
   * @param {object} scope 原始范围
   * @returns {Promise<boolean>} 原生API仍能查到时为true，404为false
   */
  async exists(scope) {
    validateDatasetScope(scope);
    if (!this.#verified) await this.verifyProject();
    const result = await this.#request(
      `/api/public/v2/datasets/${scope.name}`,
      "GET",
      undefined,
      true,
    );
    if (result == null) return false;
    if (result.name !== scope.name || !ownsDataset(result.metadata, scope))
      throw new Error("LANGFUSE_DATASET_OWNERSHIP_MISMATCH");
    return true;
  }

  /**
   * 发布固定用例的无正文条目；不接受任意输入、输出或文件。
   * @param {object} scope 原始范围
   * @param {string} caseId 五例中的一例
   * @param {number} revision 探针元数据修订，1或2
   * @returns {Promise<object>} 条目非敏感状态，仍需GET确认
   */
  async putItem(scope, caseId, revision) {
    const id = this.#item(scope, caseId);
    if (![1, 2].includes(revision)) throw new Error("LANGFUSE_DATASET_ITEM_INVALID");
    const previous = await this.#request(`/api/public/dataset-items/${id}`, "GET", undefined, true);
    if (previous != null) this.#validateItem(previous, scope, caseId);
    const result = await this.#request("/api/public/dataset-items", "POST", {
      id,
      datasetName: scope.name,
      input: { __ta_protocol_revision: revision },
      expectedOutput: null,
      metadata: { ...datasetMetadata(scope), case_id: caseId, protocol_revision: revision },
    });
    return this.#validateItem(result, scope, caseId);
  }

  /**
   * 按精确ID验证已发布条目，不查询其他数据集。
   * @param {object} scope 原始范围
   * @param {string} caseId 固定用例
   * @returns {Promise<object|null>} 当前条目；不存在为null，不表示历史版本物理清理
   */
  async getItem(scope, caseId) {
    const id = this.#item(scope, caseId);
    const result = await this.#request(`/api/public/dataset-items/${id}`, "GET", undefined, true);
    return result == null ? null : this.#validateItem(result, scope, caseId);
  }

  /**
   * 调用原生条目删除；仅表示原生API接受，不宣称历史副本消失。
   * @param {object} scope 原始范围
   * @param {string} caseId 固定用例
   * @returns {Promise<void>} 拒绝或未知结果时抛错，调用者须登记待核对状态
   */
  async removeItem(scope, caseId) {
    const id = this.#item(scope, caseId);
    await this.#request(`/api/public/dataset-items/${id}`, "DELETE", undefined, true);
  }

  /**
   * 构造固定命名空间ID，禁止未经ensure登记的写入和任意目标删除。
   * @param {object} scope 原范围
   * @param {string} caseId 固定用例
   * @returns {string} 精确条目ID
   */
  #item(scope, caseId) {
    validateDatasetScope(scope, true);
    if (!CASES.has(caseId) || !this.#datasets.has(scope.runKey)) {
      throw new Error("LANGFUSE_DATASET_ITEM_INVALID");
    }
    return `${scope.name}-${caseId}`;
  }

  /**
   * 验证返回条目确属本数据集且无正文，不把错误项目的响应当作成功。
   * @param {object} result 平台响应
   * @param {object} scope 原范围
   * @param {string} caseId 固定用例
   * @returns {object} 受限状态
   */
  #validateItem(result, scope, caseId) {
    if (
      !result ||
      result.id !== `${scope.name}-${caseId}` ||
      result.datasetId !== this.#datasets.get(scope.runKey) ||
      !ownsDataset(result.metadata, scope) ||
      Object.keys(result.metadata).length !== 7 ||
      result.metadata.case_id !== caseId ||
      ![1, 2].includes(result.input?.__ta_protocol_revision) ||
      Object.keys(result.input).length !== 1 ||
      result.input.__ta_protocol_revision !== result.metadata.protocol_revision ||
      result.expectedOutput != null
    ) {
      throw new Error("LANGFUSE_DATASET_ITEM_MISMATCH");
    }
    return { id: result.id, revision: result.metadata.protocol_revision };
  }

  /**
   * 有界本机HTTP；写入失败后不自动重发，错误不包含响应、认证或底层异常。
   * @param {string} path 类内固定构造路径
   * @param {string} method 固定HTTP方法
   * @param {object|undefined} body 无正文元数据
   * @param {boolean} missingAllowed 是否将404作为不存在
   * @returns {Promise<object|null>} 有界JSON
   */
  async #request(path, method, body, missingAllowed = false) {
    if (this.#requests >= 48) throw new Error("LANGFUSE_DATASET_REQUEST_LIMIT");
    this.#requests++;
    let response;
    try {
      response = await fetch(`${this.#endpoint}${path}`, {
        method,
        redirect: "error",
        headers: { Authorization: this.#authentication, "Content-Type": "application/json" },
        body: body == null ? undefined : JSON.stringify(body),
        signal: AbortSignal.timeout(5000),
      });
      if (response.status === 404 && missingAllowed) {
        await response.body?.cancel();
        return null;
      }
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
      if (size === 0 && method === "DELETE") return null;
      const parsed = JSON.parse(Buffer.concat(chunks, size).toString("utf8"));
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("BODY");
      return parsed;
    } catch {
      throw new Error("LANGFUSE_DATASET_REQUEST_UNCONFIRMED");
    }
  }
}
