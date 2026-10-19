package com.lawrence.travel.application.support;

import com.lawrence.travel.domain.support.ProblemState;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 反馈/运维传输模型；公开摘要与敏感反馈正文分开，客户端不能传入执行权限。 */
public final class Support {
    /** 协议命名空间。 */
    private Support() {}

    /** 可报告的私有目标。 */
    public enum Kind {
        /** 助手回复。 */
        REPLY,
        /** 附件。 */
        DOCUMENT,
        /** 建议。 */
        ADVICE,
        /** 运行状态。 */
        RUN
    }

    /** 质量反馈也可以没有异常事件。 */
    public enum Category {
        /** 理解偏差。 */
        UNDERSTANDING,
        /** 内容不准。 */
        INACCURATE,
        /** 约束遗漏。 */
        CONSTRAINT,
        /** 无法操作。 */
        UNUSABLE,
        /** 运行卡住。 */
        STUCK,
        /** 其他。 */
        OTHER
    }

    /**
     * @param kind 目标种类
     * @param scope 聊天或任务
     * @param id 精确目标
     */
    public record Target(Kind kind, UUID scope, UUID id) {}

    /**
     * @param id 原请求幂等ID
     * @param target 私有目标
     * @param category 分类
     * @param description 用户主动填写，不复制会话
     * @param consent 是否明确授权所选引用
     * @param reference 单个引用，可空
     */
    public record Input(
            UUID id,
            Target target,
            Category category,
            String description,
            boolean consent,
            UUID reference) {}

    /**
     * @param trace 原因果链
     * @param span 原尝试/Agent步骤，可空
     * @param request 原请求
     * @param chat 所属聊天，可空 @param document 关联原件，可空
     */
    public record Link(UUID trace, UUID span, UUID request, UUID chat, UUID document) {}

    /**
     * @param saved 只有事务成功才为true
     * @param id 反馈编号
     * @param problem 问题编号
     * @param request 本次关联号
     */
    public record Receipt(boolean saved, UUID id, UUID problem, UUID request) {}

    /**
     * @param id 反馈
     * @param problem 问题
     * @param category 固定分类
     * @param target 私有目标
     * @param state 问题状态
     * @param created 创建时间
     */
    public record Feedback(
            UUID id,
            UUID problem,
            Category category,
            Target target,
            ProblemState state,
            Instant created) {}

    /**
     * @param summary 本人可见摘要
     * @param description 明确授权读取的用户反馈
     * @param reference 可选原文引用
     * @param excerpt 当前仍有效的有界片段，可空或不可读提示
     */
    public record Detail(Feedback summary, String description, UUID reference, String excerpt) {}

    /**
     * @param id 聚合编号
     * @param component 固定组件
     * @param code 固定错误
     * @param severity 严重度
     * @param count 窗口次数
     * @param first 首次
     * @param last 末次
     * @param state 状态
     * @param revision 并发版本
     * @param cause 根因分类
     * @param repair 修复类别
     * @param version 修复版本
     * @param regression 回归引用
     * @param representative 代表问题
     */
    public record Group(
            UUID id,
            String component,
            String code,
            String severity,
            long count,
            Instant first,
            Instant last,
            ProblemState state,
            long revision,
            String cause,
            String repair,
            String version,
            String regression,
            UUID representative) {}

    /**
     * @param revision 期望版本
     * @param next 目标状态
     * @param cause 固定根因
     * @param repair CODE/CONFIG/PROVIDER/CLARIFICATION
     * @param version 修复标识
     * @param regression 回归用例 @param passed 是否明确验证通过
     * @param duplicateOf 重复问题目标，可空
     */
    public record Change(
            long revision,
            ProblemState next,
            String cause,
            String repair,
            String version,
            String regression,
            boolean passed,
            UUID duplicateOf) {}

    /**
     * @param id 节点
     * @param parent 父节点
     * @param kind 固定步骤
     * @param state 执行事实
     * @param code 脱敏错误码 @param started 开始
     * @param ended 结束可空
     * @param run 原运行可空
     */
    public record Span(
            UUID id,
            UUID parent,
            String kind,
            String state,
            String code,
            Instant started,
            Instant ended,
            UUID run) {}

    /**
     * @param id 问题编号
     * @param trace 因果链
     * @param span 精确节点，可空
     * @param request 原请求
     * @param created 发生时间
     */
    public record Event(UUID id, UUID trace, UUID span, UUID request, Instant created) {}

    /**
     * @param trace 目标链路
     * @param spans 最多200条，不声明完整
     * @param truncated 达到上限
     */
    public record Trace(UUID trace, List<Span> spans, boolean truncated) {}

    /**
     * @param rows 待写固定诊断队列
     * @param dropped 被限流/容量丢弃数
     * @param failures 写入失败数
     */
    public record Health(int rows, long dropped, long failures) {}
}
