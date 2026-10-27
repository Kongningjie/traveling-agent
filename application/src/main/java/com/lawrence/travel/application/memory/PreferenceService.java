package com.lawrence.travel.application.memory;

import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.PreferenceStore;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.memory.Preference;
import java.util.Set;
import java.util.UUID;

/** 用户主动管理或确认建议的用例；不向Agent暴露无限制写入，不修改独立旅行约束。 */
public final class PreferenceService {
    private final PreferenceStore store;
    private final BusinessTransactions transactions;

    /**
     * @param store 权威偏好
     * @param transactions 短事务边界
     */
    public PreferenceService(PreferenceStore store, BusinessTransactions transactions) {
        this.store = store;
        this.transactions = transactions;
    }

    /**
     * @param actor 服务端身份
     * @return 跨会话有效偏好与待确认建议
     */
    public Preference.View read(UserIdentity actor) {
        return transactions.required(() -> store.read(user(actor)));
    }

    /**
     * @param actor 身份
     * @param requestId 幂等请求
     * @param revision 全局预期版本
     * @param id 稳定条目
     * @param action 显式操作
     * @param category 类别
     * @param value 内容
     * @param city 可空通用范围
     * @return 权威保存结果
     */
    public Preference.View change(
            UserIdentity actor,
            UUID requestId,
            long revision,
            UUID id,
            String action,
            String category,
            String value,
            String city) {
        if (requestId == null
                || id == null
                || revision < 0
                || !Set.of("SAVE", "UPDATE", "SUGGEST", "CONFIRM", "DELETE")
                        .contains(action == null ? "" : action))
            throw new IllegalArgumentException("PREFERENCE_COMMAND_INVALID");
        return transactions.required(
                () ->
                        store.change(
                                user(actor),
                                requestId,
                                revision,
                                id,
                                action,
                                category,
                                value,
                                city == null ? "" : city.trim()));
    }

    /**
     * @param actor 认证身份
     * @return 禁止从客户端指定其他用户
     */
    private UUID user(UserIdentity actor) {
        if (actor == null)
            throw new com.lawrence.travel.application.travel.TravelStorageFailure(
                    com.lawrence.travel.application.travel.TravelStorageFailure.Code.NOT_FOUND);
        return actor.id();
    }
}
