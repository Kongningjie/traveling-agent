package com.lawrence.travel.infrastructure.document;

import com.lawrence.travel.application.document.Advice;
import com.lawrence.travel.application.interaction.Dialogue;
import com.lawrence.travel.application.port.AdviceStore;
import com.lawrence.travel.application.travel.TravelStorageFailure;
import com.lawrence.travel.domain.travel.TaskScope;
import com.lawrence.travel.infrastructure.interaction.DialogueMapper;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import com.lawrence.travel.infrastructure.travel.TravelMapper;
import java.util.List;
import java.util.UUID;

/** PG建议存储；用户锁串行化确认、修改、文件删除，避免重复或重叠采纳。 */
public final class PostgresAdviceStore implements AdviceStore {
    private final AdviceMapper mapper;
    private final DialogueMapper chats;
    private final TravelMapper travel;
    private final TravelJson json;
    /** @param mapper 建议SQL @param chats 聊天归属 @param travel 旅行基础 @param json 固定协议 */
    public PostgresAdviceStore(AdviceMapper mapper,DialogueMapper chats,TravelMapper travel,TravelJson json){this.mapper=mapper;this.chats=chats;this.travel=travel;this.json=json;}
    /** {@inheritDoc} 用户锁是跨聊天变更共同入口，任务/聊天锁不得反向取得。 */
    @Override public Advice.Context context(UUID user,UUID chat){return safe(()->{
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("TRANSACTION_REQUIRED");
        Long preference=mapper.preference(user);var c=chats.chat(user,chat);check(preference!=null && c!=null);
        Dialogue.Basis basis=new Dialogue.Basis(null,null,0,0,null);
        if(c.taskId()!=null){var t=travel.lockTask(new TaskScope(user,c.taskId()));check(t!=null);String run=chats.run(user,c.taskId());basis=new Dialogue.Basis(c.taskId(),run==null?null:UUID.fromString(run),t.executionGeneration(),t.constraintRevision(),t.currentVersionId());}
        Long revision=mapper.revision(user,chat);check(revision!=null);return new Advice.Context(basis,preference,revision,mapper.blocked(user,chat));
    });}
    /** {@inheritDoc} 产物仅在原聊天处理结果提交事务中插入。 */
    @Override public void insert(Advice.Entry e){safe(()->{if(mapper.insert(e,json.encode(e))!=1)throw new IllegalArgumentException("ADVICE_CAPACITY");return null;});}
    /** {@inheritDoc} 列表不决定权限，列表按版本判断，实际采纳再核对来源。 */
    @Override public List<Advice.Entry> list(UUID user,UUID chat){return safe(()->mapper.list(user,chat).stream().map(s -> json.decode(s,Advice.Entry.class)).toList());}
    /** {@inheritDoc} 跨用户和不存在均不泄露对象存在性。 */
    @Override public Advice.Entry get(UUID user,UUID chat,UUID id){return safe(()->{String body=mapper.get(user,chat,id);check(body!=null);return json.decode(body,Advice.Entry.class);});}
    /** {@inheritDoc} 回执不与业务请求拆事务。 */
    @Override public void update(Advice.Entry e){safe(()->{check(mapper.update(e,json.encode(e))==1);return null;});}
    /** @param present 归属或写入成立，否则统一不存在 */
    private static void check(boolean present){if(!present)throw new TravelStorageFailure(TravelStorageFailure.Code.NOT_FOUND);}

    /** @param work 有界SQL动作 @param <T> 结果类型 @return 固定依赖失败，不保留可能包含私有建议的SQL异常链 */
    private <T> T safe(java.util.function.Supplier<T> work){try{return work.get();}catch(org.springframework.dao.DataAccessException failure){throw new TravelStorageFailure(TravelStorageFailure.Code.DEPENDENCY_UNAVAILABLE);}}

}
