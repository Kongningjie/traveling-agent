/** 删除预览只允许有界文本和精确技术身份；不信任网络泛型断言。 */
export interface DeletePreview {
  revision: string;
  documents: number;
  versions: number;
  notice: string;
  resources: { kind: string; id: string; name: string }[];
  truncated: boolean;
}
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
/**
 * @param raw 不可信响应
 * @param kind 当前选择范围
 * @param target 当前对象
 * @returns 可展示确认清单，拒绝错对象/超限内容
 */
export function decodeDeletePreview(raw: unknown, kind: string, target: string): DeletePreview {
  if (!raw || typeof raw !== "object") throw new Error("DELETE_PROTOCOL");
  const v = raw as Record<string, unknown>;
  if (
    v.kind !== kind ||
    v.target !== target ||
    typeof v.revision !== "string" ||
    !/^[a-f0-9]{64}$/.test(v.revision) ||
    !Number.isSafeInteger(v.documents) ||
    (v.documents as number) < 0 ||
    !Number.isSafeInteger(v.versions) ||
    (v.versions as number) < 0 ||
    typeof v.notice !== "string" ||
    v.notice.length > 1200 ||
    typeof v.truncated !== "boolean" ||
    !Array.isArray(v.resources) ||
    v.resources.length > 40
  )
    throw new Error("DELETE_PROTOCOL");
  const resources = v.resources.map((item: unknown) => {
    if (!item || typeof item !== "object") throw new Error("DELETE_PROTOCOL");
    const r = item as Record<string, unknown>;
    if (
      typeof r.kind !== "string" ||
      !["CHAT", "TASK", "DOCUMENT", "VERSION"].includes(r.kind) ||
      typeof r.id !== "string" ||
      !uuid.test(r.id) ||
      typeof r.name !== "string" ||
      r.name.length > 300
    )
      throw new Error("DELETE_PROTOCOL");
    return { kind: r.kind, id: r.id, name: r.name };
  });
  return {
    revision: v.revision,
    documents: v.documents as number,
    versions: v.versions as number,
    notice: v.notice,
    resources,
    truncated: v.truncated,
  };
}
/**
 * @param raw 删除响应
 * @param request 原稳定幂等键
 * @returns 只有真实保存且已撤权才视为提交成功；后台清理单独表述
 */
export function deletionSaved(raw: unknown, request: string): boolean {
  if (!raw || typeof raw !== "object") return false;
  const v = raw as Record<string, unknown>;
  return (
    v.saved === true &&
    v.id === request &&
    v.readable === false &&
    typeof v.cleanupPending === "boolean"
  );
}
