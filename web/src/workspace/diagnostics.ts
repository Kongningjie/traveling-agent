import {savedProblem} from "./support";
import {Api} from "./client";
/** 客户端固定诊断协议，函数根本不接受原始错误、URL、DOM、表单或存储内容。 */
export type ClientCode="RENDER"|"RESOURCE"|"NETWORK"|"PROTOCOL";
let last=0,sent=0;
/** @param code 固定故障类别 @returns 实际持久化的问题ID；未确认不返回占位编号。每分钟至多一次，每页面生命周期至多10次。 */
export async function reportClient(code:ClientCode):Promise<string>{const now=Date.now();if(now-last<60000||sent>=10)return "";last=now;sent++;try{const r=await new Api().post<{saved:boolean;problem:string}>("/api/v1/support/client",{id:crypto.randomUUID(),code,component:"APP"});return savedProblem(r);}catch{return "";}}
/** 安装固定资源故障与安全API事件监听；不上传浏览器原始ErrorEvent。 */
export function installDiagnostics():void{window.addEventListener("error",event=>{void reportClient(event.target instanceof HTMLElement?"RESOURCE":"RENDER");},true);window.addEventListener("unhandledrejection",()=>{void reportClient("RENDER");});window.addEventListener("travel:network-error",()=>{void reportClient("NETWORK");});}
