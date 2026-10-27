import { readFile, writeFile, readdir } from "node:fs/promises";
import ts from "typescript";
import { parse } from "@vue/compiler-sfc";

/**
 * 只遍历项目手写前端源码和测试，不读取构建目录、依赖或配置。
 * @param directory 调用方限定的源码根或其子目录
 * @returns TypeScript和Vue源码路径
 */
async function sources(directory) {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = `${directory}/${entry.name}`;
    if (entry.isDirectory()) files.push(...(await sources(path)));
    else if (/\.(ts|vue)$/.test(entry.name)) files.push(path);
  }
  return files;
}

/**
 * 通过TypeScript语法节点定位文档注释，避免误改字符串、正则或模板中的类似文本。
 * @param source TypeScript脚本，不包含Vue模板和样式
 * @returns 按位置逆序排列的真实文档注释范围，供安全回写
 */
function comments(source) {
  const tree = ts.createSourceFile("source.ts", source, ts.ScriptTarget.Latest, true);
  const ranges = new Map();
  /**
   * 收集节点前的实际注释；同一注释可能属于父子节点，按起点去重。
   * @param node 正在访问的TypeScript语法节点
   */
  function visit(node) {
    for (const range of ts.getLeadingCommentRanges(source, node.getFullStart()) ?? []) {
      if (source.slice(range.pos, range.pos + 3) === "/**") ranges.set(range.pos, range);
    }
    ts.forEachChild(node, visit);
  }
  visit(tree);
  return [...ranges.values()].sort((a, b) => b.pos - a.pos);
}

/**
 * 展开同一行内多项标签，保留原有说明和参数描述，不生成模板化业务解释。
 * @param source 尚未排版的脚本内容
 * @returns 仅文档标签布局变化后的脚本
 */
function normalize(source) {
  for (const range of comments(source)) {
    const comment = source.slice(range.pos, range.end);
    if (!/@(?:param|returns?|throws|see|deprecated)\b/.test(comment)) continue;
    let body = comment
      .slice(3, -2)
      .replace(/\r?\n\s*\* ?/g, "\n")
      .trim();
    body = body.replace(/(?<!\{)\s+(@(?:param|returns?|throws|see|deprecated)\b)/g, "\n$1");
    const prefix = source.slice(0, range.pos).split("\n").at(-1);
    const indent = prefix.trim() ? "" : prefix;
    const expanded =
      "/**\n" +
      body
        .split(/\r?\n/)
        .map((line) => `${indent} *${line.trimEnd() ? " " + line.trimEnd() : ""}`)
        .join("\n") +
      `\n${indent} */`;
    source = source.slice(0, range.pos) + expanded + source.slice(range.end);
  }
  return source;
}

let changed = 0;
for (const file of [...(await sources("src")), ...(await sources("tests"))]) {
  const original = await readFile(file, "utf8");
  let result = original;
  if (file.endsWith(".vue")) {
    const descriptor = parse(original).descriptor;
    for (const script of [descriptor.script, descriptor.scriptSetup]
      .filter(Boolean)
      .sort((a, b) => b.loc.start.offset - a.loc.start.offset)) {
      result =
        result.slice(0, script.loc.start.offset) +
        normalize(script.content) +
        result.slice(script.loc.end.offset);
    }
  } else result = normalize(original);
  if (result !== original) {
    changed++;
    if (!process.argv.includes("--check")) await writeFile(file, result, "utf8");
  }
}
if (changed && process.argv.includes("--check"))
  throw new Error(`TSDOC_LAYOUT_INVALID_FILES=${changed}`);
console.log(`TSDOC_${process.argv.includes("--check") ? "CHECK" : "APPLY"}_PASS files=${changed}`);
