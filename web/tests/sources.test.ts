import test from "node:test";
import assert from "node:assert/strict";
import { decodeSourceFragment, decodeSourceReferences } from "../src/workspace/sources.ts";
/** 合成引用，验证定位真实可比，不包含文件或真实用户。 */
const fragment = {
  reference: "d40ba6da-7497-4e96-a002-01bfa4174df6",
  locator: { document: "d40ba6da-7497-4e96-a002-01bfa4174df7", position: 3, start: 2, end: 6 },
  text: "并非已订",
  truncated: true,
  notice: "仅页段",
};
/** 精确字符范围与截断标志必须来自服务器，不将内容当作HTML。 */
test("source fragment validates actual bounded ranges", () => {
  assert.deepEqual(decodeSourceFragment(fragment), fragment);
  for (const patch of [
    { reference: "../.env" },
    { text: "更多原文内容" },
    { truncated: "false" },
    { notice: "x".repeat(501) },
  ])
    assert.throws(() => decodeSourceFragment({ ...fragment, ...patch }));
});
/** 没有实际引用就没有入口；有效列表去重，不引入原文或任意URL。 */
test("reply source availability requires actual reference identities", () => {
  assert.deepEqual(decodeSourceReferences([]), []);
  assert.deepEqual(decodeSourceReferences([fragment.reference, fragment.reference]), [
    fragment.reference,
  ]);
});
/** 未知响应不能让页面展示误导性的来源入口。 */
test("source availability rejects invalid and excessive reference lists", () => {
  for (const value of [
    null,
    {},
    ["-".repeat(36)],
    ["../.env"],
    [null],
    Array(11).fill(fragment.reference),
  ])
    assert.throws(() => decodeSourceReferences(value));
});
/** 错误位置及不匹配长度拒绝，防止旧格式或伪引用进入页面。 */
test("source locator rejects invalid position and identity", () => {
  for (const patch of [
    { document: "other" },
    { position: 0 },
    { start: -1 },
    { end: 5 },
    { end: 2 },
  ])
    assert.throws(() =>
      decodeSourceFragment({ ...fragment, locator: { ...fragment.locator, ...patch } }),
    );
  assert.throws(() => decodeSourceFragment(null));
});
