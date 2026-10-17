import type { CandidateView, Card, View, Trip } from "./types";

/** 只在整组读取一致且后端允许时展示接受；任何字段未知均拒绝，不代替后端事务门禁。 */
export function canAccept(
  view: View | null,
  candidate: CandidateView | null,
  ready: boolean,
): boolean {
  if (
    !view ||
    !candidate ||
    !ready ||
    !candidate.acceptable ||
    view.pendingInput ||
    view.state !== "WAITING_ACCEPTANCE"
  )
    return false;
  const value = candidate.candidate;
  return (
    value.state === "PRESENTED" &&
    value.version.constraintRevision === view.constraintRevision &&
    value.version.baseVersionId === view.currentVersionId &&
    value.validation?.conclusion === "PASS" &&
    value.review?.conclusion === "PASS"
  );
}
/** 卡片必须属于当前运行、代次和需求；预选与刷新不产生回答。 */
export function canAnswer(
  view: View | null,
  card: Card | null,
  ready: boolean,
): boolean {
  return Boolean(
    ready &&
      view &&
      card &&
      !view.pendingInput &&
      card.decision.state === "OPEN" &&
      card.decision.runId === view.runId &&
      card.decision.generation === view.generation &&
      card.decision.constraintRevision === view.constraintRevision,
  );
}
/** 只允许普通网页来源，不渲染脚本、文件或认证URL。 */
export function sourceLink(value: string | null): string | undefined {
  try {
    if (!value) return undefined;
    const url = new URL(value);
    return ["https:", "http:"].includes(url.protocol) &&
      !url.username &&
      !url.password
      ? url.href
      : undefined;
  } catch {
    return undefined;
  }
}
/** 城市数量、天数与人数在前端提前提示，最终仍由领域规则校验。 */
export function tripError(
  cities: string,
  start: string,
  end: string,
  travelers: number,
): string {
  const list = cities
    .split(/[,，]/)
    .map((v) => v.trim())
    .filter(Boolean);
  const days = (Date.parse(end) - Date.parse(start)) / 86400000 + 1;
  return list.length < 1 ||
    list.length > 3 ||
    new Set(list).size !== list.length
    ? "首版支持1～3个不同城市。"
    : !Number.isInteger(days) || days < 1 || days > 10
      ? "首版支持1～10天，请检查起止日期。"
      : !Number.isInteger(travelers) || travelers < 1
        ? "同行人数必须为正整数。"
        : "";
}

/** 当前指针仍保留旧行程时，必须提示它不能代表已满足最新需求；缺失版本也保守提示。 */
export function currentNeedsValidation(
  view: View | null,
  trip: Trip | null,
): boolean {
  return Boolean(
    view && trip && trip.constraintRevision !== view.constraintRevision,
  );
}
