package com.lawrence.travel.application.knowledge;

import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.ResearchStore;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.knowledge.EvidenceRecord;
import com.lawrence.travel.domain.travel.TaskScope;
import java.util.List;
import java.util.UUID;

/** 字段证据查看与显式撤回用例；不提供手工写入已核实供应商事实的入口。 */
public final class EvidenceService {
    private final ResearchStore store;
    private final BusinessTransactions transactions;

    /**
     * @param store 私有证据端口
     * @param transactions 短事务
     */
    public EvidenceService(ResearchStore store, BusinessTransactions transactions) {
        this.store = store;
        this.transactions = transactions;
    }

    /**
     * @param actor 认证主体
     * @param task 私有任务
     * @return 最多500条最近证据及有效性状态，正文始终按纯文本展示
     */
    public List<Research.View> list(UserIdentity actor, UUID task) {
        return store.evidence(new TaskScope(actor.id(), task));
    }

    /**
     * @param actor 认证主体
     * @param task 私有任务
     * @param reference 不可变引用
     * @param request 撤回幂等身份
     * @return 是否首次追加撤回通知
     */
    public boolean withdraw(
            UserIdentity actor, UUID task, EvidenceRecord.Reference reference, UUID request) {
        return transactions.required(
                () -> store.withdraw(new TaskScope(actor.id(), task), reference, request));
    }
}
