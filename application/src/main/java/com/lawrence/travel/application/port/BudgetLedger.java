package com.lawrence.travel.application.port;

import com.lawrence.travel.domain.execution.control.CallKind;
import com.lawrence.travel.domain.execution.control.RunBudget;
import com.lawrence.travel.domain.execution.control.RunLimits;
import com.lawrence.travel.domain.execution.control.TokenUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** 持久化计量端口；调用前原子预占，未知请求不退还次数或 Token 预占。 */
public interface BudgetLedger {
    /** @param runId 输入或旅行预算 @param fallback 原旅行限制 @return 输入预算采用独立上限，旧实现保持兼容 */
    default RunLimits limitsFor(UUID runId, RunLimits fallback) { return fallback; }
    /**
     * 建立预算身份；重复调用不能重置旧计数。
     * @param runId 本次运行稳定标识
     */
    void create(UUID runId);

    /**
     * 锁定预算后预占并登记请求，在真实调用前提交。
     * @param runId 原运行标识，重试和子任务必须复用
     * @param kind 计数类别
     * @param limits 原运行限制
     * @param target 不含提示词或凭证的请求范围
     * @param tokenUpperBound 保守 Token 上界，非模型操作可为零
     * @param now 请求预占时间
     * @return 已提交的计量身份及新预算
     * @throws IllegalStateException 调用方仍在业务事务中，不能保证返回前完成独立预占提交
     */
    Reservation reserve(UUID runId, CallKind kind, RunLimits limits, Target target,
                        long tokenUpperBound, Instant now);

    /**
     * 读取现有预算，不存在时拒绝，不能自动创建零值掩盖恢复错误。
     * @param runId 原运行标识
     * @return 已保存的预算
     */
    RunBudget read(UUID runId);

    /**
     * 由唯一运行活跃区间管理者累加时间，不能按并行子请求重复累加。
     * @param runId 原运行标识
     * @param elapsed 待记账活跃区间，必须非负且为整毫秒
     */
    void addActiveTime(UUID runId, Duration elapsed);

    /**
     * 结束一次实际请求的计量，重复回调不能覆盖第一次结果。
     * @param reservationId 预占身份
     * @param usage 已知用量；null 明确表示未知，不保存为零
     * @param latency 实际请求持续时间，不直接累加到全运行主动时间
     */
    void finish(UUID reservationId, TokenUsage usage, Duration latency);

    /**
     * 汇总本项目自快照核实后的已知用量及未决请求 Token 上界。
     * @param target 精确型号、地域与接口
     * @param since 已核实快照时间
     * @return 保守计入的本项目 Token；不能代表其他程序或整个账户用量
     */
    long tokensSince(Target target, Instant since);

    /**
     * 检查未知用量或实际消耗超出预估的记录，出现时必须重新核实额度。
     * @param target 精确型号及接入范围
     * @param since 当前额度核实时间
     * @param unresolvedBefore 当前进程准入服务建立时间，此前仍未结束的请求视为不确定
     * @return 是否存在不能继续信任当前本地额度快照的记录
     */
    boolean hasUncertainUsage(Target target, Instant since, Instant unresolvedBefore);

    /**
     * 请求范围，不保存账号、密钥、提示词或原始响应。
     * @param modelId 精确型号或工具标识
     * @param region 实际地域
     * @param api 实际接口产品
     */
    record Target(String modelId, String region, String api) { }

    /**
     * 请求预占结果，用于回调计量和保守超时计算。
     * @param id 请求预占标识
     * @param budget 已持久化的新预算快照
     */
    record Reservation(UUID id, RunBudget budget) { }
}
