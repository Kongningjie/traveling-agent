import type { TaskView } from "../events/runEvents";

/** 当前认证身份，不含会话ID或凭证。 */
export interface User {
  id: string;
  loginName: string;
}
/** 服务端私有任务摘要。 */
export interface Task {
  title: string;
  task: {
    scope: { taskId: string };
    constraintRevision: number;
    currentVersionId: string | null;
  };
}
/** 已确认要求，假设与授权保留独立来源。 */
export interface Requirement {
  id: string;
  field: string;
  value: string;
  kind: string;
  source: string;
  targetIds: string[];
}
/** 只读完整行程，稳定ID用于比较及锁定。 */
export interface Trip {
  constraintRevision: number;
  window: {
    cityIds: string[];
    startsOn: string;
    endsOn: string;
    travelers: number;
  };
  cityStays: { id: string; cityId: string }[];
  days: { id: string; date: string }[];
  items: {
    id: string;
    cityStayId: string;
    type: string;
    title: string;
    locationKey: string;
    startsAt: string;
    endsAt: string;
    fixedArrangementId: string | null;
  }[];
  transportLegs: {
    id: string;
    fromItemId: string;
    toItemId: string;
    startsAt: string;
    endsAt: string;
    durationMinutes: number | null;
    estimated: boolean;
  }[];
  accommodationStays: {
    id: string;
    cityStayId: string;
    checkIn: string;
    checkOut: string;
    rooms: number;
    locationKey: string | null;
  }[];
  costEntries: {
    id: string;
    category: string;
    upper: number | null;
    lower: number;
    quantity: number;
    unit: string;
    includedInBudget: boolean;
    paid: boolean;
  }[];
  alternatives: { id: string; replacesItemId: string; description: string }[];
  evidenceRefs: { id: string; revision: number }[];
}
/** 不可变元信息，浏览历史不会获得接受资格。 */
export interface Version {
  snapshotId: string;
  baseVersionId: string | null;
  constraintRevision: number;
  payloadHash: string;
}
/** 历史摘要；accepted表示曾接受，不表示当前生效。 */
export interface VersionSummary {
  version: Version;
  createdAt: string;
  accepted: boolean;
}
/** 历史正文。 */
export interface VersionDetail {
  summary: VersionSummary;
  snapshot: Trip;
  sourceCode?: string;
  sourceMessages?: Message[];
}
/** Java或独立审核问题，UNKNOWN不能隐含通过。 */
export interface Report {
  conclusion: string;
  issues: {
    code: string;
    objectId: string | null;
    blocking: boolean;
    unknown: boolean;
  }[];
}
/** 当前运行候选，acceptable是后端提示，接受时仍重新校验。 */
export interface CandidateView {
  candidate: {
    candidateId: string;
    version: Version;
    snapshot: Trip;
    state: string;
    validation: Report | null;
    review: Report | null;
  };
  costs: { lowerCny: number; upperCny: number | null } | null;
  acceptable: boolean;
}
/** 可靠消息，仅展示后端已保存内容及处理状态。 */
export interface Message {
  id: string;
  kind: string;
  text: string;
  facts: Fact[];
  state: string;
}
/** 明确用户输入；无需模型解析的字段不会自动推断。 */
export interface Fact {
  field: string;
  value: string;
  quote: null;
}
/** 单个版本化问题，推荐只是视觉建议。 */
export interface Question {
  id: string;
  prompt: string;
  options: Record<string, string>;
  recommendedOption: string | null;
}
/** 可部分回答、有限授权的卡片。 */
export interface Card {
  decision: {
    id: string;
    runId: string;
    generation: number;
    revision: number;
    constraintRevision: number;
    state: string;
    remaining: Record<string, Question>;
    allowedAuthorizationScope: string[];
  };
  specs: Record<string, { field: string; reason: string; tradeoff: string }>;
}
/** 版本字段差异，字符串通过Vue转义展示。 */
export interface Change {
  kind: string;
  id: string;
  action: string;
  before: Record<string, string>;
  after: Record<string, string>;
}
/** 字段证据及有效状态；来源链接在组件内检查协议。 */
export interface Evidence {
  evidence: {
    reference: { id: string };
    targetId: string;
    fieldPath: string;
    value: string;
    sourceUrl: string | null;
    retrievedAt: string;
    expiresAt: string;
  };
  effectiveStatus: string;
  reason: string;
}
/** 工作台一致视图，沿用既有SSE契约。 */
export type View = TaskView;
