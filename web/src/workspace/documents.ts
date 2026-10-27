/** 私有附件最小视图，不含服务端路径或正文。 */
export type DocumentView = {
  id: string;
  name: string;
  kind: string;
  size: number;
  state: string;
  code: string;
  sections: number;
  notice: string;
};
/**
 * @param value 未验证HTTP结果
 * @returns 完整检查后的有界附件列表；未知协议不进入页面。
 */
export function decodeDocumentList(value: unknown): DocumentView[] {
  if (!Array.isArray(value) || value.length > 10) throw new Error("DOCUMENT_RESPONSE_INVALID");
  return value.map((v) => {
    if (
      !v ||
      typeof v !== "object" ||
      typeof v.id !== "string" ||
      !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v.id) ||
      typeof v.name !== "string" ||
      v.name.length > 160 ||
      !["pdf", "docx", "txt", "md"].includes(v.kind) ||
      !Number.isInteger(v.size) ||
      v.size < 1 ||
      v.size > 10485760 ||
      ![
        "READY",
        "QUEUED",
        "RUNNING",
        "WAITING_USER",
        "WAITING_DEPENDENCY",
        "PAUSED",
        "CANCELLED",
        "SUPERSEDED",
        "FAILED",
      ].includes(v.state) ||
      typeof v.notice !== "string" ||
      v.notice.length > 500 ||
      typeof v.code !== "string" ||
      !/^[A-Z0-9_]{0,64}$/.test(v.code) ||
      !Number.isInteger(v.sections) ||
      v.sections < 0 ||
      v.sections > 10000 ||
      (v.state === "READY" && (v.code !== "OK" || v.sections === 0))
    )
      throw new Error("DOCUMENT_RESPONSE_INVALID");
    return v as DocumentView;
  });
}
/**
 * @param item 已检查状态
 * @returns 固定用户说明，READY才宣称已读取。
 */
export function documentLabel(item: DocumentView): string {
  return (
    (
      {
        READY: `已读取 ${item.sections} 页／段`,
        QUEUED: "等待解析",
        RUNNING: "正在隔离解析",
        PAUSED: "解析暂停，请检查文件或服务后重试",
        CANCELLED: "已停止",
        SUPERSEDED: "资料已变化，请重新上传",
        FAILED: "解析失败",
      } as Record<string, string>
    )[item.state] ?? "等待确认状态"
  );
}
