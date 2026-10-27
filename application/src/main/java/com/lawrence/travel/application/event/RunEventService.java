package com.lawrence.travel.application.event;

import com.lawrence.travel.application.port.RunEventStore;
import com.lawrence.travel.application.travel.TravelStorageFailure;
import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.travel.TaskScope;
import java.util.UUID;

/**
 * 用户可见旅行事件的应用读取服务，串接RunEventController/EventStreams与RunEventStore。
 *
 * <p>以已认证用户生成TaskScope，校验复合游标属于当前任务并限制页大小；PG返回的水位
 * 用于补读已提交事件，不接受客户端时间戳或自报运行代次替代权威游标。
 * 本类不写业务、不调用模型、不取消任务，也不把诊断内部轨迹直接当作用户消息展示。
 */
public final class RunEventService {
    private final RunEventStore store;

    /**
     * @param store 权威私有读取
     */
    public RunEventService(RunEventStore store) {
        this.store = store;
    }

    /**
     * @param actor 认证身份
     * @param task 任务
     * @return 一致性任务视图
     */
    public RunEvents.View view(UserIdentity actor, UUID task) {
        return store.view(scope(actor, task));
    }

    /**
     * @param actor 认证身份
     * @param task 任务
     * @param after 任务复合游标或首次空值
     * @param limit 1～100
     * @return 连续页，不将分页结果游标推进到尚未读到的事件
     */
    public RunEvents.Page page(UserIdentity actor, UUID task, String after, int limit) {
        if (limit < 1 || limit > 100)
            throw new TravelStorageFailure(TravelStorageFailure.Code.INVALID_INPUT);
        return store.page(scope(actor, task), sequence(task, after), limit);
    }

    /**
     * @param task 当前任务
     * @param cursor 完整游标，首次可空
     * @return 精确 long；拒绝跨任务、负数、溢出、非规范形式
     */
    public static long sequence(UUID task, String cursor) {
        if (cursor == null) return 0;
        String prefix = task + ":";
        try {
            if (!cursor.startsWith(prefix)) throw new IllegalArgumentException();
            String number = cursor.substring(prefix.length());
            if (!number.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
            return Long.parseLong(number);
        } catch (IllegalArgumentException invalid) {
            throw new TravelStorageFailure(TravelStorageFailure.Code.INVALID_INPUT);
        }
    }

    /**
     * @param actor 认证身份
     * @param task 私有任务
     * @return 认证归属；匿名和缺失统一不可见
     */
    private static TaskScope scope(UserIdentity actor, UUID task) {
        if (actor == null || task == null)
            throw new TravelStorageFailure(TravelStorageFailure.Code.NOT_FOUND);
        return new TaskScope(actor.id(), task);
    }
}
