package com.lawrence.travel.application.port;

import com.lawrence.travel.application.lifecycle.Lifecycle;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** PG删除权威和恢复屏障；所有修改由短事务控制，不接收任意SQL或文件路径。 */
public interface LifecycleStore {
    /**
     * @param user 用户
     * @param kind 范围
     * @param target 精确对象
     * @return 用户锁下的确认范围，跨用户与缺失统一拒绝
     */
    Lifecycle.Preview preview(UUID user, Lifecycle.Kind kind, UUID target);

    /**
     * @return 加锁的部署身份/水位，首次未初始化也返回0
     */
    Lifecycle.Head head();

    /**
     * @return 不加写序列锁的恢复屏障水位
     */
    Lifecycle.Head status();

    /**
     * @return 是否已经建立独立删除清单，旧备份不能重新初始化代替丢失清单
     */
    boolean initialized();

    /**
     * @param user 当前主体
     * @param id 幂等请求
     * @return 已有墓碑，可空
     */
    Lifecycle.Entry replay(UUID user, UUID id);

    /**
     * @param user 归属
     * @param task 旅行可空
     * @return 需先通过既有引擎取消的运行
     */
    List<UUID> runs(UUID user, UUID task);

    /**
     * @param entry 可靠清单中的最小墓碑；重放与原删除使用同一实现
     */
    void apply(Lifecycle.Entry entry);

    /**
     * @param head 独立清单的已应用水位，和删除同事务
     */
    void advance(Lifecycle.Head head);

    /**
     * @param now 受控当前时间
     * @return 至多30个待回收原件；停止/读取不依赖回收器
     */
    List<Lifecycle.File> files(Instant now);

    /**
     * @param id 实际删除成功的原件
     */
    void purged(UUID id);

    /**
     * @param file 失败项
     * @param now 受控时钟，固定码重试和逾期可观察
     */
    void failed(Lifecycle.File file, Instant now);

    /**
     * @param now 受控时间
     * @return 本次保留期清理数量，不清业务接受/授权审计
     */
    int prune(Instant now);

    /**
     * @param now 当前时间
     * @return 固定清理状态
     */
    Lifecycle.Health health(Instant now);

    /**
     * @param user 当前归属
     * @param chat 所属聊天
     * @param document 待删来源
     * @return 仅来源解析和已经读取旧来源的聊天作业，不包含无关附件解析
     */
    List<UUID> sourceJobs(UUID user, UUID chat, UUID document);

    /**
     * @param entry 本人已确认墓碑 @return 仅此删除范围尚有原件待清理，不泄露其他用户的清理队列
     */
    boolean pending(Lifecycle.Entry entry);

    /**
     * @param request 全局唯一请求UUID @return 已被其他归属占用；只能在全局序列锁内校验，避免先写清单后才发现唯一冲突
     */
    boolean requestUsed(UUID request);
}
