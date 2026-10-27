package com.lawrence.travel.application.port;

import com.lawrence.travel.application.document.Advice;
import java.util.List;
import java.util.UUID;

/** 建议持久化，调用方短事务内通过context取得用户→任务→聊天锁。 */
public interface AdviceStore {
    /**
     * @param user 已认证用户
     * @param chat 私有聊天
     * @return 当前基础及水位；不存在拒绝
     */
    Advice.Context context(UUID user, UUID chat);

    /**
     * @param entry 已验证来源的建议，不保存第二份文件正文
     */
    void insert(Advice.Entry entry);

    /**
     * @param user 用户
     * @param chat 聊天
     * @return 最近40项，仅返回实际完成分析的结果
     */
    List<Advice.Entry> list(UUID user, UUID chat);

    /**
     * @param user 用户
     * @param chat 聊天
     * @param id 精确建议
     * @return 归属匹配建议，缺失统一拒绝
     */
    Advice.Entry get(UUID user, UUID chat, UUID id);

    /**
     * @param entry 已经持有用户锁的最终回执；与变更请求同事务
     */
    void update(Advice.Entry entry);
}
