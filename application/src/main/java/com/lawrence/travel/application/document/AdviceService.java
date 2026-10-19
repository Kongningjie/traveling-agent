package com.lawrence.travel.application.document;

import com.lawrence.travel.application.interaction.Dialogue;
import com.lawrence.travel.application.interaction.Negotiation;
import com.lawrence.travel.application.interaction.NegotiationService;
import com.lawrence.travel.application.port.AdviceStore;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.DialogueStore;
import com.lawrence.travel.application.port.TravelStore;
import com.lawrence.travel.application.safety.SafetyGate;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.interaction.PlanAdvice;
import com.lawrence.travel.domain.interaction.RequirementFields;
import com.lawrence.travel.domain.travel.ConstraintRevision;
import com.lawrence.travel.domain.travel.TaskScope;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 只读分析与逐项采纳；模型产物不直接写旅行，确认后沿原需求/Patch/审核/接受流程执行。 */
public final class AdviceService {
    private final AdviceStore store;
    private final SourceReadService sources;
    private final DialogueStore chats;
    private final NegotiationService negotiation;
    private final TravelStore travel;
    private final BusinessTransactions tx;
    private final SafetyGate safety;
    /** @param store 建议权威 @param sources 来源校验 @param chats 原聊天关联 @param negotiation 既有变更用例 @param travel 已确认需求 @param tx 同库短事务 @param safety 输入输出检查 */
    public AdviceService(AdviceStore store,SourceReadService sources,DialogueStore chats,NegotiationService negotiation,TravelStore travel,BusinessTransactions tx,SafetyGate safety){
        this.store=store;this.sources=sources;this.chats=chats;this.negotiation=negotiation;this.travel=travel;this.tx=tx;this.safety=safety;
    }
    /** @param c 已通过原领取guard @param drafts 仅只读意图允许的结构化建议；与回复一起提交 */
    public void capture(Dialogue.Claim c,List<PlanAdvice.Draft> drafts){
        if(drafts.isEmpty())return;
        var context=store.context(c.userId(),c.chatId());
        if(!context.basis().equals(c.basis()))throw stale();
        for(var draft:drafts){
            var reference=sources.agentReference(c.budgetId(),c.token(),draft.reference());
            if(!reference.text().contains(draft.quote()))throw new IllegalArgumentException("ADVICE_QUOTE_INVALID");
            store.insert(new Advice.Entry(UUID.randomUUID(),c.userId(),c.chatId(),c.messageId(),reference.locator().document(),new Advice.Context(context.basis(),context.preference(),context.documents(),false),draft,"OPEN",Map.of(),null));
        }
    }
    /** @param actor 已认证身份 @param chat 私有聊天 @return 当前可见建议；旧偏好不再通过建议恢复 */
    public List<Advice.View> list(UserIdentity actor,UUID chat){return tx.required(() -> {
        var current=store.context(actor.id(),chat);
        return store.list(actor.id(),chat).stream().filter(e -> e.context().preference()==current.preference()).map(e -> {
            // 原件和片段不可变；删除/偏好触发器清除派生物，列表只比版本，不每2秒装载40份全文。
            String state=e.state();
            if(state.equals("OPEN") && !same(e.context(),current))state="STALE";
            return new Advice.View(e.id(),e.message(),e.document(),e.draft(),state,e.task(),e.applied());
        }).toList();
    });}
    /** @param actor 已认证用户 @param chat 私有聊天 @param id 服务端建议 @param action ADOPT/CUSTOM/REJECT @param custom 自定义字段，不能越过原建议影响字段 @param confirmed 用户已查看具体字段/范围并再次确认 @return 最终或原始幂等回执；不代表行程生效 */
    public Advice.View act(UserIdentity actor,UUID chat,UUID id,String action,Map<String,String> custom,boolean confirmed){
        if(actor==null || chat==null || id==null || (action==null || !java.util.Set.of("ADOPT","CUSTOM","REJECT").contains(action)) || custom==null || !confirmed)throw new IllegalArgumentException("ADVICE_CONFIRMATION_REQUIRED");
        return tx.required(() -> {
            // 用户锁串行化所有建议；第一项登记变更后代次立即提升，重叠旧建议须重新分析。
            var current=store.context(actor.id(),chat);var e=store.get(actor.id(),chat,id);
            var changes=action.equals("CUSTOM")?Map.copyOf(custom):e.draft().changes();
            if(!action.equals("CUSTOM") && !custom.isEmpty() || action.equals("CUSTOM") && (changes.isEmpty() || !e.draft().changes().keySet().containsAll(changes.keySet())))throw new IllegalArgumentException("ADVICE_SCOPE_INVALID");
            String target=action.equals("REJECT")?"REJECTED":"APPLIED";
            if(!e.state().equals("OPEN")){
                if(!e.state().equals(target) || target.equals("APPLIED") && !e.applied().equals(changes))throw stale();
                return view(e);
            }
            if(!same(e.context(),current) || !sourceValid(e))throw stale();
            UUID task=null;
            if(!action.equals("REJECT")){
                if(current.blocked() || changes.isEmpty() || !e.draft().pending().isBlank())throw new IllegalArgumentException("ADVICE_REQUIRES_CLARIFICATION");
                var requirements=current.basis().taskId()==null?List.<ConstraintRevision.Requirement>of():travel.revision(new TaskScope(actor.id(),current.basis().taskId()),current.basis().revision()).requirements();
                RequirementFields.merge(requirements,changes,ConstraintRevision.Source.USER);
                // 规范字段仅在用户确认时转成明确需求原文，不把文件中的“打算/可选”偷换成固定预订。
                String text="已明确确认采纳以下需求变更（尚未生成或接受新行程）："+new java.util.TreeMap<>(changes);
                safety.require("INPUT",text);
                var facts=changes.entrySet().stream().map(v -> new Negotiation.Fact(v.getKey(),v.getValue(),v.getValue())).toList();
                task=negotiation.applyAdvice(actor,current.basis().taskId(),e.id(),text,facts);
                if(current.basis().taskId()==null)chats.linkConfirmed(actor.id(),chat,task);
            }
            var saved=new Advice.Entry(e.id(),e.user(),e.chat(),e.message(),e.document(),e.context(),e.draft(),target,target.equals("APPLIED")?changes:Map.of(),task);
            store.update(saved);return view(saved);
        });
    }
    /** @param e 建议 @return 来源仍有效；不把过期定位当作已核实原文 */
    private boolean sourceValid(Advice.Entry e){try{var ref=sources.reference(e.user(),e.chat(),e.draft().reference());return ref.locator().document().equals(e.document()) && ref.text().contains(e.draft().quote());}catch(DocumentFailure missing){return false;}}
    /** @param a 原快照 @param b 当前快照 @return 比较业务/偏好/资料，blocked仅在执行时另行判定 */
    private static boolean same(Advice.Context a,Advice.Context b){return Objects.equals(a.basis(),b.basis()) && a.preference()==b.preference() && a.documents()==b.documents();}
    /** @param e 已保存回执 @return 不宣称修改生效的视图 */
    private static Advice.View view(Advice.Entry e){return new Advice.View(e.id(),e.message(),e.document(),e.draft(),e.state(),e.task(),e.applied());}
    /** @return 版本冲突固定错误，不泄露原文 */
    private static com.lawrence.travel.application.travel.TravelStorageFailure stale(){return new com.lawrence.travel.application.travel.TravelStorageFailure(com.lawrence.travel.application.travel.TravelStorageFailure.Code.STALE_VERSION);}
}
