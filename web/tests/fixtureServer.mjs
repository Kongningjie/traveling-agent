/** 仅回环地址的浏览器测试夹具：所有身份、报告、行程与证据均为合成数据，不连接真实服务。
 * 先 npm run build，再 node tests/fixtureServer.mjs；不可用作实际后端或生产服务。
 */
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { resolve, extname, relative, isAbsolute } from "node:path";
const dist = fileURLToPath(new URL("../dist/", import.meta.url));
const task = "00000000-0000-4000-8000-000000000001",
    second = "00000000-0000-4000-8000-000000000002";
const base = "00000000-0000-4000-8000-000000000010",
    next = "00000000-0000-4000-8000-000000000011";
const run = "00000000-0000-4000-8000-000000000020",
    question = "00000000-0000-4000-8000-000000000030",
    option = "00000000-0000-4000-8000-000000000031";
let sequence = 1,
    authenticated = true,
    current = base,
    generation = 1,
    state = "WAITING_ACCEPTANCE",
    stale = false,
    cardVisible = true;
const calls = [],
    streams = new Set();
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
        baseVersionId: id === next ? base : null,
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
    for (const stream of streams)
        stream.write(
            `id: ${task}:${sequence}\nevent: run-event\ndata: ${JSON.stringify({ id: `${task}:${sequence}`, sequence: String(sequence), runId: run, generation, type: "RUN_STATUS", sourceType: "RUN_QUEUED", state })}\n\n`,
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
                    authenticated
                        ? { id: "synthetic-user", username: "本地测试账号" }
                        : { code: "UNAUTHORIZED" },
                    authenticated ? 200 : 401,
                );
            if (request.method === "POST") {
                let text = "";
                for await (const part of request) text += part;
                const body = text ? JSON.parse(text) : {};
                if (path.endsWith("/auth/login")) {
                    authenticated = true;
                    return json(response, {
                        id: "synthetic-user",
                        username: "本地测试账号",
                    });
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
                    cardVisible = false;
                    changed();
                    return json(response, { status: "RECORDED" }, 202);
                }
                if (path.endsWith("/messages")) {
                    changed();
                    return json(response, { status: "RECEIVED" }, 202);
                }
                return json(response, { taskId: task }, 202);
            }
            if (!authenticated)
                return json(response, { code: "UNAUTHORIZED" }, 401);
            if (path === "/api/v1/tasks")
                return json(
                    response,
                    [task, second].map((id, i) => ({
                        title: i ? "单城市测试任务" : "本地夹具 · 三城市十天",
                        task: {
                            scope: { taskId: id },
                            constraintRevision: 1,
                            currentVersionId: base,
                        },
                    })),
                );
            const isSecond = path.includes(second);
            if (path.endsWith("/view"))
                return json(response, {
                    taskId: isSecond ? second : task,
                    constraintRevision: 1,
                    generation,
                    currentVersionId: current,
                    runId: run,
                    state,
                    pendingInput: false,
                    cursor: `${isSecond ? second : task}:${sequence}`,
                    requirements: {
                        requirements: [
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
                    itinerary: current === next ? candidateTrip : trip,
                });
            if (path.endsWith("/messages"))
                return json(response, [
                    {
                        id: "message",
                        kind: "CHANGE",
                        text: "这是一份本地合成旅行任务，用于验证界面。",
                        facts: [],
                        state: "APPLIED",
                    },
                ]);
            if (path.endsWith("/decision"))
                return json(
                    response,
                    cardVisible
                        ? {
                              decision: {
                                  id: "decision",
                                  runId: run,
                                  generation,
                                  revision: 1,
                                  constraintRevision: 1,
                                  state: "OPEN",
                                  remaining: {
                                      [question]: {
                                          id: question,
                                          prompt: "预算口径由你选择",
                                          options: { [option]: "按全组总额" },
                                          recommendedOption: option,
                                      },
                                  },
                                  allowedAuthorizationScope: [question],
                              },
                              specs: {
                                  [question]: {
                                      field: "budgetRule",
                                      reason: "方便比较整次旅行开支",
                                      tradeoff: "选择后仍需明确包含项目",
                                  },
                              },
                          }
                        : null,
                );
            if (path.endsWith("/candidate"))
                return json(response, {
                    candidate: {
                        candidateId: "synthetic-candidate",
                        version: version(next),
                        snapshot: candidateTrip,
                        state: current === next ? "ACCEPTED" : "PRESENTED",
                        validation: { conclusion: "PASS", issues: [] },
                        review: { conclusion: "PASS", issues: [] },
                    },
                    costs: { lowerCny: 0, upperCny: null },
                    acceptable:
                        current !== next && state === "WAITING_ACCEPTANCE",
                });
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
                streams.add(response);
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
server.listen(4189, "127.0.0.1", () =>
    process.stdout.write(
        "Local synthetic UI fixture ready: http://127.0.0.1:4189\n",
    ),
);
