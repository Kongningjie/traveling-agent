/** 仅回环地址的浏览器测试夹具：所有身份、报告、行程与证据均为合成数据，不连接真实服务。
 * 先 npm run build，再 node tests/fixtureServer.mjs；不可用作实际后端或生产服务。
 */
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { resolve, extname, relative, isAbsolute } from "node:path";
import { randomUUID } from "node:crypto";
import syntheticUser from "./fixtures/identity.json" with { type: "json" };
const dist = fileURLToPath(new URL("../dist/", import.meta.url));
const task = "00000000-0000-4000-8000-000000000001",
    second = "00000000-0000-4000-8000-000000000002";
const base = "00000000-0000-4000-8000-000000000010",
    next = "00000000-0000-4000-8000-000000000011";
const run = "00000000-0000-4000-8000-000000000020",
    question = "00000000-0000-4000-8000-000000000030",
    option = "00000000-0000-4000-8000-000000000031";
const scopeQuestion = "00000000-0000-4000-8000-000000000032",
    scopeOption = "00000000-0000-4000-8000-000000000033";
let sequence = 1,
    authenticated = process.env.TRAVEL_UI_FIXTURE_AUTHENTICATED !== "false",
    current = base,
    generation = 1,
    state = "WAITING_USER",
    stale = false,
    cardVisible = true;
let preferenceRevision = 0;
const preferenceEntries = new Map(),
    preferenceReceipts = new Map();
const calls = [],
    streams = new Map();
let created = null;
let cardRevision = 1,
    budgetAnswered = false;
const savedMessages = new Map();
const dialogueChats = new Map();
// 可选的建议UI场景；所有ID/文本/回执为合成，不是实际模型分析。
const adviceChat="00000000-0000-4000-8000-000000000060",adviceMessage="00000000-0000-4000-8000-000000000061",adviceId="00000000-0000-4000-8000-000000000062",adviceDocument="00000000-0000-4000-8000-000000000063",adviceReference="00000000-0000-4000-8000-000000000064";
const adviceFixture=process.env.TRAVEL_UI_FIXTURE_ADVICE==="true";
const adviceItem={id:adviceId,message:adviceMessage,document:adviceDocument,state:"OPEN",task:null,applied:{},draft:{reference:adviceReference,quote:"打算一天只玩一个景点，住宿尚未预订。",statement:"INTENDED",problem:"留出休息时间",reason:"这是用户材料中的打算，开放时间仍需核实。",proposal:"保留慢游节奏，减少跨区域往返。",impact:"当前旅行的节奏偏好",evidence:"USER_MATERIAL",pending:"",changes:{pace:"慢游"}}};
if(adviceFixture)dialogueChats.set(adviceChat,{chat:{id:adviceChat,title:"合成计划分析",taskId:null},messages:[{id:adviceMessage,text:"检查我的计划，先不要开始规划",state:"DONE",reply:"以下仅为本地UI夹具建议，尚未采纳或生效。",operation:null,error:""}],blocked:false});

/** @param n 正整数 @returns 固定测试日期，避免依赖真实天气或日历服务。 */
function date(n) {
    return `2026-11-${String(n).padStart(2, "0")}`;
}
const cities = ["北京", "天津", "上海"];
const trip = {
    constraintRevision: 1,
    window: {
        cityIds: cities,
        startsOn: date(1),
        endsOn: date(10),
        travelers: 4,
    },
    cityStays: cities.map((cityId, i) => ({ id: `city-${i}`, cityId })),
    days: Array.from({ length: 10 }, (_, i) => ({
        id: `day-${i}`,
        date: date(i + 1),
    })),
    items: Array.from({ length: 10 }, (_, i) => ({
        id: `item-${i}`,
        cityStayId: `city-${i < 3 ? 0 : i < 6 ? 1 : 2}`,
        type: "REST",
        title: i === 0 ? "本地夹具安排" : `第${i + 1}天休息`,
        locationKey: "测试地点",
        startsAt: `${date(i + 1)}T10:00:00+08:00[Asia/Shanghai]`,
        endsAt: `${date(i + 1)}T11:00:00+08:00[Asia/Shanghai]`,
        fixedArrangementId: null,
    })),
    transportLegs: [],
    accommodationStays: [],
    costEntries: [],
    alternatives: [
        {
            id: "alt",
            replacesItemId: "item-0",
            description: "测试备选，不代表真实调查结论",
        },
    ],
    evidenceRefs: [],
};
const candidateTrip = structuredClone(trip);
candidateTrip.items[0].title = "<img src=x onerror=alert(1)>测试文字应转义";
/** @param id 版本身份 @returns 合成元信息，明确不使用真实供应商哈希。 */
function version(id) {
    return {
        snapshotId: id,
        baseVersionId:
            id === next ? (created && current === null ? null : base) : null,
        constraintRevision: 1,
        payloadHash: "a".repeat(64),
    };
}
/** @param response HTTP连接 @param body 合成结果 @param status 状态 @returns 无；不输出请求凭据。 */
function json(response, body, status = 200) {
    response.writeHead(status, {
        "Content-Type": "application/json;charset=utf-8",
        "Cache-Control": "no-store",
    });
    response.end(JSON.stringify(body));
}
/** 递增合成水位并触发SSE，事件不包含用户原文。 */
function changed() {
    sequence++;
    for (const [stream, taskId] of streams)
        stream.write(
            `id: ${taskId}:${sequence}\nevent: run-event\ndata: ${JSON.stringify({ id: `${taskId}:${sequence}`, sequence: String(sequence), runId: run, generation, type: "RUN_STATUS", sourceType: "RUN_QUEUED", state })}\n\n`,
        );
}
/** 处理仅本机夹具的静态文件及模拟API，不调用供应商。 */
const server = createServer(async (request, response) => {
    try {
        const url = new URL(request.url, "http://127.0.0.1"),
            path = url.pathname;
        if (path === "/__test/state")
            return json(response, {
                writes: calls.map((c) => ({
                    path: c.path,
                    action: c.body?.response?.action,
                    selectedCount: Object.keys(c.body?.response?.selected ?? {})
                        .length,
                    requestId: c.body?.requestId ?? c.body?.inputId,
                    text: c.body?.text ?? c.body?.response?.text,
                    factsCount: c.body?.facts?.length,
                    authorizationCount: c.body?.response?.authorization?.length,
                })),
                current,
                state,
                authenticated,
            });
        if (path === "/__test/expire") {
            authenticated = false;
            return json(response, { status: "EXPIRED" });
        }
        if (path === "/__test/stale") {
            stale = true;
            return json(response, { status: "NEXT_WRITE_CONFLICT" });
        }
        if (path.startsWith("/api/")) {
            if (path.endsWith("/auth/csrf"))
                return json(response, {
                    headerName: "X-CSRF-TOKEN",
                    token: "__SYNTHETIC_TEST_ONLY__",
                });
            if (path.endsWith("/auth/me"))
                return json(
                    response,
                    authenticated ? syntheticUser : { code: "UNAUTHORIZED" },
                    authenticated ? 200 : 401,
                );
            if(adviceFixture && path===`/api/v1/chats/${adviceChat}/advice` && request.method==="GET")return json(response,[adviceItem]);
            if(adviceFixture && path===`/api/v1/chats/${adviceChat}/advice/${adviceId}/actions` && request.method==="POST"){
                let raw="";for await(const part of request)raw+=part;const body=JSON.parse(raw);calls.push({path,method:request.method,body});
                if(!body.confirmed)return json(response,{code:"CONFIRM_REQUIRED"},400);
                adviceItem.state=body.action==="REJECT"?"REJECTED":"APPLIED";adviceItem.applied=body.action==="CUSTOM"?body.changes:{...adviceItem.draft.changes};return json(response,adviceItem);
            }
            if(path.endsWith("/advice")&&request.method==="GET")return json(response,[]);
            if(adviceFixture&&path===`/api/v1/chats/${adviceChat}/documents`&&request.method==="GET")return json(response,[]);
            if(adviceFixture&&path===`/api/v1/chats/${adviceChat}/references/${adviceReference}`)return json(response,{reference:adviceReference,locator:{document:adviceDocument,hash:"a".repeat(64),parser:"synthetic-v1",position:1,start:0,end:adviceItem.draft.quote.length},text:adviceItem.draft.quote,truncated:false,notice:"仅本地合成原文。"});
            // 以下为确定的本地UI夹具，模拟结果不代表真实模型分类。
            if (path.startsWith("/api/v1/chats")) {
                if (!authenticated) return json(response, { code: "UNAUTHORIZED" }, 401);
                if (request.method === "GET") {
                    if (path === "/api/v1/chats") return json(response, [...dialogueChats.values()].map((v) => v.chat));
                    const value = dialogueChats.get(path.split("/")[4]);
                    return json(response, value ?? { code: "NOT_FOUND" }, value ? 200 : 404);
                }
                let raw = ""; for await (const part of request) raw += part;
                const body = JSON.parse(raw); calls.push({ path, method: request.method, body });
                const id = path.split("/")[4];
                let view = dialogueChats.get(id);
                if (path.endsWith("/link")) {
                    const old = [...dialogueChats.values()].find((v) => v.chat.taskId === body.taskId);
                    if (old) return json(response, old);
                    view = { chat: { id, title: created?.title ?? "本地旅行夹具", taskId: body.taskId }, messages: [], blocked: false };
                    dialogueChats.set(id, view); return json(response, view);
                }
                if (!view) { view = { chat: { id, title: body.text.slice(0, 60), taskId: null }, messages: [], blocked: false }; dialogueChats.set(id, view); }
                if (path.endsWith("/actions")) {
                    const m = view.messages.find((m) => m.id === path.split("/")[6]);
                    if (m) m.state = body.action === "WITHDRAW" ? "WITHDRAWN" : "DONE";
                    return json(response, view);
                }
                if (!view.messages.some((m) => m.id === body.inputId)) view.messages.push({ id: body.inputId, text: body.text, state: body.text === "记住我不吃辣" ? "CONFIRM" : "DONE", reply: "这是本地夹具回复。你可以先咨询，明确规划后再建立旅行任务。", operation: body.text === "记住我不吃辣" ? { action: "SAVE_PREFERENCE", description: "保存长期偏好：饮食不吃辣；适用于所有城市。" } : null, error: "" });
                return json(response, view);
            }
            if (path.startsWith("/api/v1/preferences")) {
                if (!authenticated)
                    return json(response, { code: "UNAUTHORIZED" }, 401);
                const view = () => ({
                    version: {
                        userId: syntheticUser.id,
                        revision: preferenceRevision,
                    },
                    entries: [...preferenceEntries.values()].filter(
                        (p) => p.state !== "DELETED",
                    ),
                });
                if (request.method === "GET") return json(response, view());
                let text = "";
                for await (const part of request) text += part;
                const body = JSON.parse(text);
                calls.push({ path, method: request.method, body });
                if (preferenceReceipts.has(body.requestId))
                    return json(response, view());
                if (body.revision !== preferenceRevision)
                    return json(response, { code: "STALE_VERSION" }, 409);
                const id = body.id ?? path.split("/")[4],
                    old = preferenceEntries.get(id);
                if (request.method === "DELETE")
                    preferenceEntries.set(id, {
                        ...old,
                        value: "",
                        state: "DELETED",
                    });
                else if (path.endsWith("/confirm"))
                    preferenceEntries.set(id, { ...old, state: "CONFIRMED" });
                else
                    preferenceEntries.set(id, {
                        id,
                        category: body.category,
                        value: body.value,
                        city: body.city ?? "",
                        source: body.suggestion ? "SUGGESTION" : "USER",
                        state: body.suggestion ? "PENDING" : "CONFIRMED",
                        updatedAt: "2026-10-17T12:00:00Z",
                    });
                preferenceRevision++;
                preferenceReceipts.set(body.requestId, true);
                return json(response, view());
            }
            if (request.method === "POST") {
                let text = "";
                for await (const part of request) text += part;
                const body = text ? JSON.parse(text) : {};
                if (path.endsWith("/auth/login")) {
                    authenticated = true;
                    return json(response, syntheticUser);
                }
                if (!authenticated)
                    return json(response, { code: "UNAUTHORIZED" }, 401);
                if (stale) {
                    stale = false;
                    return json(response, { code: "STALE_VERSION" }, 409);
                }
                calls.push({ path, body });
                if (path.endsWith("/auth/logout")) {
                    authenticated = false;
                    response.writeHead(204);
                    return response.end();
                }
                if (path.endsWith("/accept")) {
                    current = next;
                    state = "SUCCEEDED";
                    changed();
                    return json(response, { status: "ACCEPTED" });
                }
                if (path.endsWith("/restore")) {
                    state = "QUEUED";
                    generation++;
                    changed();
                    return json(
                        response,
                        { status: "QUEUED", runId: run },
                        202,
                    );
                }
                if (path.endsWith("/answers")) {
                    // 只供UI流程：选预算口径但未补范围时保留另一问题；不声称做了LLM语义理解。
                    if (
                        !cardVisible ||
                        body.response?.revision !== cardRevision
                    )
                        return json(response, { code: "STALE_VERSION" }, 409);
                    if (["REJECT", "UNDECIDED"].includes(body.response?.action))
                        return json(response, { status: "RECORDED" }, 202);
                    budgetAnswered =
                        Boolean(body.response?.selected?.[question]) ||
                        budgetAnswered;
                    cardVisible =
                        !body.response?.text?.trim() &&
                        !body.response?.selected?.[scopeQuestion];
                    cardRevision++;
                    state = cardVisible ? "WAITING_USER" : "WAITING_ACCEPTANCE";
                    const id = path.split("/")[4];
                    savedMessages.set(id, [
                        ...(savedMessages.get(id) ?? []),
                        {
                            id: randomUUID(),
                            kind: "ANSWER",
                            text:
                                body.response?.text || "已提交选择：按全组总额",
                            facts: [],
                            state: "APPLIED",
                        },
                    ]);
                    changed();
                    return json(response, { status: "RECORDED" }, 202);
                }
                if (path.endsWith("/messages")) {
                    const id = path.split("/")[4];
                    savedMessages.set(id, [
                        ...(savedMessages.get(id) ?? []),
                        {
                            id: randomUUID(),
                            kind: "CHANGE",
                            text: body.text,
                            facts: body.facts ?? [],
                            state: "APPLIED",
                        },
                    ]);
                    state = "WAITING_USER";
                    cardVisible = true;
                    generation++;
                    cardRevision++;
                    budgetAnswered = false;
                    changed();
                    return json(response, { status: "RECEIVED" }, 202);
                }
                if (path === "/api/v1/tasks") {
                    created = { id: randomUUID(), title: body.title };
                    savedMessages.set(created.id, [
                        {
                            id: randomUUID(),
                            kind: "CHANGE",
                            text: body.text,
                            facts: body.facts ?? [],
                            state: "APPLIED",
                        },
                    ]);
                    state = "WAITING_USER";
                    cardVisible = true;
                    generation++;
                    cardRevision++;
                    budgetAnswered = false;
                    current = null;
                    changed();
                    return json(response, { taskId: created.id }, 202);
                }
                return json(response, { taskId: task }, 202);
            }
            if (!authenticated)
                return json(response, { code: "UNAUTHORIZED" }, 401);
            if (path === "/api/v1/tasks")
                return json(
                    response,
                    [...(created ? [created.id] : []), task, second].map(
                        (id) => ({
                            title:
                                id === created?.id
                                    ? created.title
                                    : id === second
                                      ? "单城市测试任务"
                                      : "本地夹具 · 三城市十天",
                            task: {
                                scope: { taskId: id },
                                constraintRevision: 1,
                                currentVersionId:
                                    id === created?.id ? current : base,
                            },
                        }),
                    ),
                );
            const isSecond = path.includes(second);
            if (path.endsWith("/view"))
                return json(response, {
                    taskId: path.split("/")[4],
                    constraintRevision: 1,
                    generation,
                    currentVersionId: current,
                    runId: run,
                    state,
                    pendingInput: false,
                    cursor: `${path.split("/")[4]}:${sequence}`,
                    requirements: {
                        requirements: [
                            {
                                id: "synthetic-pace",
                                field: "pace",
                                value: "合成建议：轻松",
                                kind: "SOFT",
                                source: "USER",
                                targetIds: [],
                            },
                            {
                                id: "req",
                                field: "cities",
                                value: isSecond ? "北京" : cities.join(","),
                                kind: "HARD",
                                source: "USER",
                                targetIds: [],
                            },
                        ],
                    },
                    itinerary:
                        current === null
                            ? null
                            : current === next
                              ? candidateTrip
                              : trip,
                });
            if (path.endsWith("/messages"))
                return json(
                    response,
                    savedMessages.get(path.split("/")[4]) ?? [
                        {
                            id: "message",
                            kind: "CHANGE",
                            text: "这是一份本地合成旅行任务，用于验证界面。",
                            facts: [],
                            state: "APPLIED",
                        },
                    ],
                );
            if (path.endsWith("/decision"))
                return json(
                    response,
                    cardVisible
                        ? {
                              decision: {
                                  id: "decision",
                                  runId: run,
                                  generation,
                                  revision: cardRevision,
                                  constraintRevision: 1,
                                  state: "OPEN",
                                  remaining: {
                                      ...(!budgetAnswered
                                          ? {
                                                [question]: {
                                                    id: question,
                                                    prompt: "预算口径由你选择",
                                                    options: {
                                                        [option]: "按全组总额",
                                                    },
                                                    recommendedOption: option,
                                                },
                                            }
                                          : {}),
                                      [scopeQuestion]: {
                                          id: scopeQuestion,
                                          prompt: "预算主要想包含哪些开支？",
                                          options: {
                                              [scopeOption]: "先只算游玩和吃饭",
                                          },
                                          recommendedOption: null,
                                      },
                                  },
                                  allowedAuthorizationScope: budgetAnswered
                                      ? [scopeQuestion]
                                      : [question, scopeQuestion],
                              },
                              specs: {
                                  [question]: {
                                      field: "budgetRule",
                                      reason: "方便比较整次旅行开支",
                                      tradeoff: "选择后仍需明确包含项目",
                                  },
                                  [scopeQuestion]: {
                                      field: "budgetScope",
                                      reason: "交通与住宿也可以单独考虑",
                                      tradeoff:
                                          "现在不确定，也可以直接说说自己的想法",
                                  },
                              },
                          }
                        : null,
                );
            if (path.endsWith("/candidate"))
                return json(
                    response,
                    ["WAITING_ACCEPTANCE", "SUCCEEDED"].includes(state)
                        ? {
                              candidate: {
                                  candidateId: "synthetic-candidate",
                                  version: version(next),
                                  snapshot: candidateTrip,
                                  state:
                                      current === next
                                          ? "ACCEPTED"
                                          : "PRESENTED",
                                  validation: {
                                      conclusion: "PASS",
                                      issues: [],
                                  },
                                  review: { conclusion: "PASS", issues: [] },
                              },
                              costs: { lowerCny: 0, upperCny: null },
                              acceptable:
                                  current !== next &&
                                  state === "WAITING_ACCEPTANCE",
                          }
                        : null,
                );
            if (path.endsWith("/evidence"))
                return json(response, [
                    {
                        evidence: {
                            reference: { id: "ref" },
                            targetId: "synthetic",
                            fieldPath: "weather",
                            value: "预报范围外，保持未知",
                            sourceUrl: "javascript:alert(1)",
                            retrievedAt: "2026-10-17T00:00:00Z",
                            expiresAt: "2026-10-18T00:00:00Z",
                        },
                        effectiveStatus: "UNKNOWN",
                        reason: "SYNTHETIC_TEST",
                    },
                ]);
            if (path.endsWith("/versions"))
                return json(
                    response,
                    [next, base].map((id) => ({
                        version: version(id),
                        createdAt: "2026-10-17T00:00:00Z",
                        accepted: id === base || current === next,
                    })),
                );
            if (path.endsWith("/version-diff"))
                return json(response, [
                    {
                        kind: "Item",
                        id: "item-0",
                        action: "CHANGED",
                        before: { title: trip.items[0].title },
                        after: { title: candidateTrip.items[0].title },
                    },
                ]);
            if (path.includes("/versions/")) {
                const id = path.split("/").at(-1);
                return json(response, {
                    summary: {
                        version: version(id),
                        createdAt: "2026-10-17T00:00:00Z",
                        accepted: id === base || current === next,
                    },
                    snapshot: id === next ? candidateTrip : trip,
                });
            }
            if (path.endsWith("/events/stream")) {
                response.writeHead(200, {
                    "Content-Type": "text/event-stream",
                    "Cache-Control": "no-cache",
                });
                response.write(": synthetic local fixture\n\n");
                streams.set(response, path.split("/")[4]);
                request.on("close", () => streams.delete(response));
                return;
            }
            return json(response, { code: "NOT_FOUND" }, 404);
        }
        const file = resolve(dist, "." + (path === "/" ? "/index.html" : path));
        const rel = relative(dist, file);
        if (rel.startsWith("..") || isAbsolute(rel))
            return json(response, { code: "NOT_FOUND" }, 404);
        const data = await readFile(file);
        response.writeHead(200, {
            "Content-Type":
                {
                    ".html": "text/html;charset=utf-8",
                    ".js": "text/javascript",
                    ".css": "text/css",
                }[extname(file)] ?? "application/octet-stream",
        });
        response.end(data);
    } catch {
        json(response, { code: "FIXTURE_ERROR" }, 500);
    }
});
const port = Number(process.env.TRAVEL_UI_FIXTURE_PORT ?? 4189);
if (!Number.isInteger(port) || port < 1024 || port > 65535)
    throw new Error("INVALID_LOCAL_FIXTURE_PORT");
server.listen(port, "127.0.0.1", () =>
    process.stdout.write(
        `Local synthetic UI fixture ready: http://127.0.0.1:${port}\n`,
    ),
);
