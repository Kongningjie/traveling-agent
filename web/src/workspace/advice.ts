/** 用户材料建议；字段内容均按纯文本展示，APPLIED仅表示变更收件。 */
export interface AdviceView { id:string; message:string; document:string; state:string; task:string|null; applied?:Record<string,string>; draft:{reference:string;quote:string;statement:string;problem:string;reason:string;proposal:string;impact:string;evidence:string;pending:string;changes:Record<string,string>} }
/** @param raw 未知HTTP结果 @returns 有界建议协议，错误时显示同步失败而非白屏。 */
export function decodeAdvice(raw:unknown):AdviceView[] {
  if(!Array.isArray(raw)||raw.length>40)throw new Error("ADVICE_INVALID");
  return raw.map(item=>{
    if(!item||typeof item!=="object")throw new Error("ADVICE_INVALID");
    const v=item as AdviceView,d=v.draft;
    if(![v.id,v.message,v.document,d?.reference].every(x=>typeof x==="string"&&/^[0-9a-f-]{36}$/i.test(x))||!d||!["OPEN","STALE","REJECTED","APPLIED"].includes(v.state)||!["BOOKED","INTENDED","OPTIONAL","UNKNOWN"].includes(d.statement)||!["USER_MATERIAL","UNKNOWN"].includes(d.evidence)||![d.quote,d.problem,d.reason,d.proposal,d.impact,d.pending].every(x=>typeof x==="string")||!d.changes||typeof d.changes!=="object"||Array.isArray(d.changes)||Object.entries(d.changes).some(([k,x])=>!k||typeof x!=="string"||x.length>500))throw new Error("ADVICE_INVALID");
    return v;
  });
}

/** @param field 受控业务字段 @returns 用户可读名称，不在确认卡展示模型协议键名。 */
export function adviceFieldLabel(field:string):string {
  return ({cities:"目的地",startDate:"出发日期",endDate:"结束日期",travelers:"同行人数",budgetRule:"预算口径",budgetCny:"预算金额",budgetScope:"预算包含范围",pace:"旅行节奏",diet:"饮食偏好",interests:"兴趣偏好",hardRequirements:"必须保留的要求",changeTargets:"调整范围"} as Record<string,string>)[field]??"旅行要求";
}
