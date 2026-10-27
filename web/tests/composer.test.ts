import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { pathToFileURL } from "node:url";
import { parse, compileScript } from "@vue/compiler-sfc";
import ts from "typescript";
import { createRenderer, h, nextTick, reactive } from "vue";

const require = createRequire(import.meta.url);
const source = readFileSync(new URL("../src/components/ChatComposer.vue", import.meta.url), "utf8");
const compiled = compileScript(parse(source).descriptor, {
  id: "composer-test",
  inlineTemplate: true,
});
const javascript = ts
  .transpileModule(compiled.content, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
  })
  .outputText.replace(
    /from (["'])vue\1/g,
    `from "${pathToFileURL(require.resolve("vue/dist/vue.runtime.esm-bundler.js")).href}"`,
  )
  .replaceAll(
    'from "../workspace/documents"',
    `from "${new URL("../src/workspace/documents.ts", import.meta.url).href}"`,
  );
const Component = (
  await import(`data:text/javascript;base64,${Buffer.from(javascript).toString("base64")}`)
).default;
/** 本地渲染树的最小结构，不模拟HTTP、登录状态或真实浏览器。 */
interface TestNode {
  type: string;
  children: TestNode[];
  props: Record<string, unknown>;
  parent: TestNode | null;
  text: string;
  style: Record<string, string>;
  selectionStart: number;
  scrollHeight: number;
  focus: () => void;
}
/** 无浏览器、无网络的Vue自定义渲染节点；仅模拟组件需要的textarea契约。 */
function element(type: string): TestNode {
  return {
    type,
    children: [],
    props: {},
    parent: null,
    text: "",
    style: {},
    selectionStart: 0,
    scrollHeight: 80,
    focus() {},
  };
}
const renderer = createRenderer<TestNode, TestNode>({
  createElement: element,
  createText: (text: string) => ({ ...element("text"), text }),
  createComment: () => element("comment"),
  setText: (node: TestNode, text: string) => {
    node.text = text;
  },
  setElementText: (node: TestNode, text: string) => {
    node.text = text;
  },
  patchProp: (node: TestNode, key: string, _previous: unknown, value: unknown) => {
    node.props[key] = value;
  },
  insert: (node: TestNode, parent: TestNode, anchor: TestNode | null | undefined) => {
    node.parent = parent;
    const at = anchor ? parent.children.indexOf(anchor) : -1;
    if (at < 0) parent.children.push(node);
    else parent.children.splice(at, 0, node);
  },
  remove: (node: TestNode) => {
    if (!node.parent) return;
    const index = node.parent.children.indexOf(node);
    if (index >= 0) node.parent.children.splice(index, 1);
  },
  parentNode: (node: TestNode) => node.parent,
  nextSibling: (node: TestNode) =>
    node.parent?.children[node.parent.children.indexOf(node) + 1] ?? null,
});
const id = "00000000-0000-4000-8000-000000000001",
  other = "00000000-0000-4000-8000-000000000002";
/**
 * @param node 根节点
 * @param predicate 查找条件
 * @returns 当前可见渲染节点。
 */
function nodes(node: TestNode, predicate: (node: TestNode) => boolean): TestNode[] {
  return [
    ...(predicate(node) ? [node] : []),
    ...node.children.flatMap((child: TestNode) => nodes(child, predicate)),
  ];
}
/**
 * @param node 渲染节点
 * @param name Vue事件属性
 * @param argument 合成事件；只调用当前组件注册的处理器。
 */
function event(node: TestNode, name: string, argument: unknown = undefined): void {
  (node.props[name] as (argument: unknown) => void)(argument);
}
/**
 * @returns 合成附件组件及用户事件，不装载真实应用或后端。
 */
function fixture() {
  const state = reactive({
    modelValue: "",
    disabled: false,
    documents: [
      {
        id,
        name: "计划.txt",
        kind: "txt",
        size: 20,
        state: "READY",
        code: "OK",
        sections: 1,
        notice: "",
      },
      {
        id: other,
        name: "计划.txt",
        kind: "txt",
        size: 40,
        state: "READY",
        code: "OK",
        sections: 1,
        notice: "",
      },
    ],
    selectedDocuments: [] as string[],
  });
  let submitted = 0;
  const root = element("root");
  const app = renderer.createApp({
    render: () =>
      h(Component, {
        ...state,
        "onUpdate:modelValue": (value: string) => (state.modelValue = value),
        "onUpdate:selectedDocuments": (value: string[]) => (state.selectedDocuments = value),
        onSubmit: () => {
          submitted++;
        },
      }),
  });
  app.mount(root);
  /**
   * @param value 输入原文
   * @param caret 光标位置；保留中文连续输入。
   */
  async function type(value: string, caret = value.length) {
    const input = nodes(root, (n) => n.type === "textarea")[0];
    input.selectionStart = caret;
    event(input, "onInput", { target: { value } });
    await nextTick();
  }
  /**
   * @param key 用户键
   * @param extra 输入法/换行修饰；不访问系统键盘。
   */
  async function key(key: string, extra = {}) {
    event(nodes(root, (n) => n.type === "textarea")[0]!, "onKeydown", {
      key,
      preventDefault() {},
      ...extra,
    });
    await nextTick();
  }
  return { state, root, app, type, key, submissions: () => submitted };
}
test("中文连续@选择文件不暴露ID，Enter选择不立即发送", async () => {
  const f = fixture();
  try {
    await f.type("请分析@");
    assert.equal(nodes(f.root, (n) => n.props.role === "option").length, 2);
    await f.key("ArrowDown");
    await f.key("Enter");
    assert.deepEqual([...f.state.selectedDocuments], [other]);
    assert.equal(f.state.modelValue, "请分析");
    assert.equal(f.submissions(), 0);
    await f.key("Enter");
    assert.equal(f.submissions(), 1);
  } finally {
    f.app.unmount();
  }
});
test("文件解析中阻止发送；完成后正常发送，移除引用不删除原件", async () => {
  const f = fixture();
  try {
    f.state.selectedDocuments = [id];
    f.state.documents[0]!.state = "QUEUED";
    await f.type("帮我优化这个计划");
    await f.key("Enter");
    assert.equal(f.submissions(), 0);
    f.state.documents[0]!.state = "READY";
    await nextTick();
    await f.key("Enter");
    assert.equal(f.submissions(), 1);
    event(nodes(f.root, (n) => n.props["aria-label"] === "移除计划.txt引用")[0]!, "onClick");
    await nextTick();
    assert.deepEqual([...f.state.selectedDocuments], []);
    assert.equal(f.state.documents.length, 2);
  } finally {
    f.app.unmount();
  }
});
test("输入法Enter和Shift Enter不误发，Escape关闭选择，光标后的文本保留", async () => {
  const f = fixture();
  try {
    await f.type("请看@计划，后文", 6);
    await f.key("Enter", { isComposing: true });
    assert.equal(f.submissions(), 0);
    assert.equal(f.state.selectedDocuments.length, 0);
    await f.type("请看@ 后文", 3);
    await f.key("Escape");
    assert.equal(nodes(f.root, (n) => n.props.role === "listbox").length, 0);
    await f.key("Enter", { shiftKey: true });
    assert.equal(f.submissions(), 0);
    await f.type("请看@ 后文", 3);
    await f.key("Enter");
    assert.equal(f.state.modelValue, "请看 后文");
    assert.equal(f.state.selectedDocuments[0], id);
  } finally {
    f.app.unmount();
  }
});
