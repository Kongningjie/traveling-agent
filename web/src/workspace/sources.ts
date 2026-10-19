/** 有界原文引用；字符位置使用服务端UTF-16索引，不携带任何宿主路径。 */
export type SourceFragment = { reference: string; locator: { document: string; position: number; start: number; end: number }; text: string; truncated: boolean; notice: string };
/** @param value 不可信HTTP响应 @returns 校验后的原文定位；不接受伪UUID或失配字符范围。 */
export function decodeSourceFragment(value: unknown): SourceFragment {
  const v = value as SourceFragment, uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
  if (!v || typeof v.reference !== "string" || !uuid.test(v.reference) || !v.locator || typeof v.locator.document !== "string" || !uuid.test(v.locator.document)
      || !Number.isInteger(v.locator.position) || !Number.isInteger(v.locator.start) || !Number.isInteger(v.locator.end)
      || v.locator.position < 1 || v.locator.start < 0 || v.locator.end <= v.locator.start
      || typeof v.text !== "string" || v.text.length > 4000 || v.locator.end - v.locator.start !== v.text.length
      || typeof v.truncated !== "boolean" || typeof v.notice !== "string" || v.notice.length > 500) throw new Error("SOURCE_RESPONSE_INVALID");
  return v;
}
