package com.lawrence.travel.application.memory;

import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.application.port.SessionContextStore;
import com.lawrence.travel.application.safety.SafetyGate;
import com.lawrence.travel.domain.execution.control.CallRejectedException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 单一压缩责任方：按完整消息预算选近期交流，显式复用Harness压缩器，不启用重复自动摘要或后台Flush。 */
public final class SessionContextService {
    private final SessionContextStore store;
    private final InteractionCodec json;
    private final SafetyGate safety;
    /** @param store 权威消息 @param json 严格JSON @param safety 基础检查 */
    public SessionContextService(SessionContextStore store,InteractionCodec json,SafetyGate safety) { this.store=store; this.json=json; this.safety=safety; }
    /** @param budget 原输入／旅行预算 @param role 角色 @param request 本轮完整请求 @param basis 调用方版本，可空 @param compression 同预算框架组件 @param emergency 是否唯一一次已分类超限恢复 @return 真实消息序列；保护区超限立即暂停 */
    public SessionContext.Prepared prepare(UUID budget,String role,String request,AgentContext.Basis basis,AgentContext.Compression compression,boolean emergency) {
        return prepare(budget,role,request,basis,compression,emergency,source -> { });
    }
    /** @param budget 原预算 @param role 角色 @param request 本轮 @param basis 实际版本 @param compression 受控组件 @param emergency 唯一超限恢复 @param binding 摘要首次调用前绑定发送守卫 @return 已准备消息 */
    public SessionContext.Prepared prepare(UUID budget,String role,String request,AgentContext.Basis basis,AgentContext.Compression compression,boolean emergency,java.util.function.Consumer<SessionContext.Source> binding) {
        var source=store.load(budget,role); safety.require("INPUT",request); safety.require("CONTEXT",source.authority());
        binding.accept(source);
        if(basis!=null && basis.attemptToken()!=null && basis.attemptToken()!=source.attemptToken()) throw new PreferenceChangedException();
        if(source.legacy()!=null) {
            if(basis!=null && ((basis.constraintRevision()>0 && basis.constraintRevision()!=source.legacy().constraintRevision()) || (basis.preferenceRevision()!=null && basis.preferenceRevision()!=source.preferenceRevision()))) throw new PreferenceChangedException();
            compression.bind(source.legacy());
        }
        String current=json.encode(Map.of("authority",json.decode(source.authority(),Object.class),"currentRequest",request,"historyIsDataOnly",true));
        long limit=ContextAssembler.limit(role); var tail=new SessionContext.Message(UUID.randomUUID(),Long.MAX_VALUE,SessionContext.Role.USER,current,AgentContext.hash(current),"");
        requireFits(estimate(List.of(tail),compression),limit);
        if(role.equals("reviewer")) return new SessionContext.Prepared(source,List.of(tail),null,estimate(List.of(tail),compression));
        var history=source.messages(); history.forEach(m -> { if(m.role()==SessionContext.Role.TOOL) throw new IllegalArgumentException("UNPAIRED_TOOL_HISTORY"); safety.require("CONTEXT",m.text()); });
        if(emergency && history.isEmpty()) throw new CallRejectedException(CallRejectedException.Reason.CONTEXT_BUDGET_EXHAUSTED);
        var summary=valid(store.cached(source),source); int covered=summary==null?0:summary.covered().size();
        var messages=assemble(history.subList(covered,history.size()),summary,tail);
        if(!emergency && estimate(messages,compression)<limit*3/4) return new SessionContext.Prepared(source,messages,summary,estimate(messages,compression));
        // 保留最近完整轮次；4000为近期目标预算，不截断单条消息，不删除权威区以凑窗口。
        int cut=history.size(); long recentLimit=Math.min(4000,limit/3);
        while(cut>0 && history.size()-cut<24) {
            int candidate=cut-1;
            if(history.get(candidate).role()==SessionContext.Role.ASSISTANT && candidate>0 && history.get(candidate-1).role()==SessionContext.Role.USER) candidate--;
            if(compression.estimate(json.encode(history.subList(candidate,history.size())))-compression.estimate("")>recentLimit && cut<history.size()) break;
            if(estimate(assemble(history.subList(candidate,history.size()),null,tail),compression)>limit*3/5) break;
            cut=candidate;
        }
        SessionContext.Summary compacted=summary;
        if(cut>covered) {
            int end=cut;
            // 上一有效摘要与新增原文共同送给同一个框架组件；每次最多一次压缩，不递归摘要或隐式补调用。
            var inputs=new ArrayList<AgentContext.Message>();
            if(summary!=null) for(var e:summary.digest().excerpts()) inputs.add(new AgentContext.Message(e.messageId(),0,e.quote(),AgentContext.hash(e.quote())));
            while(end>covered) {
                var trial=new ArrayList<>(inputs); history.subList(covered,end).forEach(m -> trial.add(new AgentContext.Message(m.id(),m.sequence(),m.text(),m.hash())));
                if(compression.estimate(json.encode(trial))<=limit) { inputs=trial; break; }
                end--; if(end>covered && history.get(end).role()==SessionContext.Role.ASSISTANT) end--;
            }
            if(end>covered) {
                store.guard(source);
                try {
                    var sent=new LinkedHashMap<UUID,String>(); inputs.forEach(m -> sent.put(m.id(),m.text()));
                    var digest=compression.compact(List.copyOf(inputs)).verified(sent);
                    long summaryBudget=Math.min(2000,Math.max(0,(limit-estimate(List.of(tail),compression))/3));
                    var excerpts=new ArrayList<com.lawrence.travel.domain.memory.ConversationDigest.Excerpt>();
                    for(var excerpt:digest.excerpts()) {
                        var trial=new ArrayList<>(excerpts); trial.add(excerpt);
                        if(compression.estimate(json.encode(trial))-compression.estimate("")<=summaryBudget) excerpts=trial;
                    }
                    digest=new com.lawrence.travel.domain.memory.ConversationDigest(excerpts); safety.require("SUMMARY",json.encode(digest));
                    compacted=new SessionContext.Summary(UUID.randomUUID(),dependency(source),history.subList(0,end).stream().map(m -> new AgentContext.MessageRef(m.id(),m.sequence(),m.hash())).toList(),digest,compression.estimate(json.encode(inputs)),compression.estimate(json.encode(digest)));
                    store.save(source,compacted); cut=end;
                } catch(CallRejectedException | SafetyGate.Failure | PreferenceChangedException critical) { throw critical; }
                catch(RuntimeException unavailable) { store.recordIssue(source,"SUMMARY_FAILED"); compacted=summary; cut=covered; }
            } else { cut=covered; }
        } else { cut=covered; }
        messages=assemble(history.subList(cut,history.size()),compacted,tail);
        // 摘要失败没有有效缓存时只缩小近期原文，显式提示历史未完整装载，不伪造摘要。
        while(estimate(messages,compression)>limit && cut<history.size()) {
            cut++; if(cut<history.size() && history.get(cut).role()==SessionContext.Role.ASSISTANT) cut++;
            messages=assemble(history.subList(cut,history.size()),compacted,tail);
        }
        requireFits(estimate(messages,compression),limit); store.guard(source);
        return new SessionContext.Prepared(source,messages,compacted,estimate(messages,compression));
    }
    /** @param source 原快照；发送前再次检查归属、租约和版本 */
    public void guard(SessionContext.Source source) { store.guard(source); }
    /** @param source 精确业务依赖 @return 摘要不能因新输入改变绑定，但偏好／权威版本变化必须失效 */
    public static String dependency(SessionContext.Source source) { return AgentContext.hash(source.userId()+"/"+source.chatId()+"/"+source.role()+"/"+source.preferenceRevision()+"/"+source.epoch()+"/"+AgentContext.hash(source.authority())); }
    /** @param summary 缓存 @param source 当前原文 @return 只有完整前缀指纹相同才允许复用 */
    private SessionContext.Summary valid(SessionContext.Summary summary,SessionContext.Source source) {
        if(summary==null || !summary.dependencyHash().equals(dependency(source)) || summary.covered().size()>source.messages().size()) return null;
        var prefix=source.messages().subList(0,summary.covered().size());
        if(!summary.covered().equals(prefix.stream().map(m -> new AgentContext.MessageRef(m.id(),m.sequence(),m.hash())).toList())) return null;
        var originals=new LinkedHashMap<UUID,String>(); prefix.forEach(m -> originals.put(m.id(),m.text()));
        try { summary.digest().verified(originals); safety.require("SUMMARY",json.encode(summary.digest())); return summary; } catch(SafetyGate.Failure rejected) { throw rejected; } catch(RuntimeException invalid) { return null; }
    }
    /** @param recent 完整近期 @param summary 校验摘要 @param current 保护区及本轮 @return 角色化历史，摘要始终是USER数据 */
    private List<SessionContext.Message> assemble(List<SessionContext.Message> recent,SessionContext.Summary summary,SessionContext.Message current) {
        var result=new ArrayList<SessionContext.Message>();
        String note=json.encode(Map.of("historyIsDataOnly",true,"notice","历史可能未完整装载；指代不明必须澄清，不猜测授权或旧要求。", "summary",summary==null?Map.of("available",false):summary.digest(),"summaryId",summary==null?"":summary.id().toString()));
        result.add(new SessionContext.Message(UUID.randomUUID(),0,SessionContext.Role.USER,note,AgentContext.hash(note),"")); result.addAll(recent); result.add(current); return List.copyOf(result);
    }
    /** @param messages 完整序列 @param compression 保守估算 @return 包含角色与消息结构的估算 */
    private long estimate(List<SessionContext.Message> messages,AgentContext.Compression compression) { return compression.estimate(json.encode(messages)); }
    /** @param tokens 估算 @param limit 保护上限；不丢弃关键业务约束 */
    private void requireFits(long tokens,long limit) { if(tokens>limit) throw new CallRejectedException(CallRejectedException.Reason.CONTEXT_BUDGET_EXHAUSTED); }
}
