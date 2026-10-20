/** 有界原文引用；字符位置使用服务端UTF-16索引，不携带任何宿主路径。 */
export type SourceFragment = { reference: string; locator: { document: string; position: number; start: number; end: number }; text: string; truncated: boolean; notice: string };
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
/** @param value 不可信HTTP响应 @returns 校验后的原文定位；不接受伪UUID或失配字符范围。 */
export function decodeSourceFragment(value: unknown): SourceFragment {
  const v = value as SourceFragment;
  if (!v || typeof v.reference !== "string" || !uuid.test(v.reference) || !v.locator || typeof v.locator.document !== "string" || !uuid.test(v.locator.document)
      || !Number.isInteger(v.locator.position) || !Number.isInteger(v.locator.start) || !Number.isInteger(v.locator.end)
      || v.locator.position < 1 || v.locator.start < 0 || v.locator.end <= v.locator.start
      || typeof v.text !== "string" || v.text.length > 4000 || v.locator.end - v.locator.start !== v.text.length
      || typeof v.truncated !== "boolean" || typeof v.notice !== "string" || v.notice.length > 500) throw new Error("SOURCE_RESPONSE_INVALID");
  return v;
}
/** @param value 回复实际引用编号响应 @returns 有界、去重的UUID列表；空列表不应呈现来源入口。
 * @throws Error 不可信结构或编号不符合协议时拒绝，不把错误响应当成引用。 */
export function decodeSourceReferences(value: unknown): string[] {
  if (!Array.isArray(value) || value.length > 10 || value.some(id => typeof id !== "string" || !uuid.test(id))) throw new Error("SOURCE_RESPONSE_INVALID");
  return [...new Set(value as string[])];
}
