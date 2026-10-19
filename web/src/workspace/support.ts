/** 报告目标只含身份引用，不能包含回复全文或文件内容。 */
export type ReportTarget={kind:"REPLY"|"DOCUMENT"|"ADVICE"|"RUN";scope:string;id:string};
/** @param value 服务器或本地标识 @returns 是否UUID，不把任意字符串当问题编号展示。 */
export function supportId(value:unknown):value is string{return typeof value==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);}
/** @param id 本次稳定幂等ID @param target 精确目标 @param category 固定分类 @param description 用户主动输入 @returns 白名单投影，不展开目标额外字段或自动授权材料。 */
export function feedbackCommand(id:string,target:ReportTarget,category:string,description:string):object{if(!supportId(id)||!supportId(target.scope)||!supportId(target.id)||!["REPLY","DOCUMENT","ADVICE","RUN"].includes(target.kind)||!["UNDERSTANDING","INACCURATE","CONSTRAINT","UNUSABLE","STUCK","OTHER"].includes(category)||description.length>1000)throw new Error("SUPPORT_INPUT_INVALID");return {id,target:{kind:target.kind,scope:target.scope,id:target.id},category,description,consent:false,reference:null};}
/** @param value 未验证回执 @returns 只有明确saved及真实问题ID才确认；未知/失败返回空串。 */
export function savedProblem(value:unknown):string{if(!value||typeof value!=="object")return "";const r=value as Record<string,unknown>;return r.saved===true&&supportId(r.problem)?r.problem:"";}

/** @param value 固定反馈分类 @returns 面向用户的中文名称，未知分类不直接展示内部代码。 */
export function feedbackLabel(value:string):string{return ({UNDERSTANDING:"理解偏差",INACCURATE:"内容不准",CONSTRAINT:"遗漏约束",UNUSABLE:"无法操作",STUCK:"执行卡住",OTHER:"其他"} as Record<string,string>)[value]??"其他";}
/** @param value 问题状态 @returns 可理解的处理进度，重试不表述为已解决。 */
export function problemStateLabel(value:string):string{return ({NEW:"已收到",TRIAGED:"已分类",IN_PROGRESS:"处理中",RESOLVED:"已修复，待验证",VERIFIED:"已验证",REOPENED:"重新处理",DUPLICATE:"已关联重复问题"} as Record<string,string>)[value]??"状态待同步";}
