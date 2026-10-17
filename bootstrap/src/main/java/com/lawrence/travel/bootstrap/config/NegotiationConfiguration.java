package com.lawrence.travel.bootstrap.config;

import com.lawrence.travel.agent.runtime.NegotiationInterpreter;
import com.lawrence.travel.application.execution.RunService;
import com.lawrence.travel.application.execution.StageOutput;
import com.lawrence.travel.application.interaction.NegotiationHandler;
import com.lawrence.travel.application.interaction.NegotiationService;
import com.lawrence.travel.application.port.AgentRuntimePort;
import com.lawrence.travel.application.port.BusinessTransactions;
import com.lawrence.travel.application.port.CandidateStore;
import com.lawrence.travel.application.port.ExecutionEngineStore;
import com.lawrence.travel.application.port.InputInterpreter;
import com.lawrence.travel.application.port.InteractionCodec;
import com.lawrence.travel.application.port.NegotiationStore;
import com.lawrence.travel.application.port.StageProjection;
import com.lawrence.travel.application.travel.TravelStorageService;
import com.lawrence.travel.domain.execution.ExecutionFence;
import com.lawrence.travel.domain.execution.StageType;
import com.lawrence.travel.infrastructure.execution.persistence.EngineMapper;
import com.lawrence.travel.infrastructure.interaction.InteractionJson;
import com.lawrence.travel.infrastructure.interaction.NegotiationMapper;
import com.lawrence.travel.infrastructure.interaction.PostgresNegotiationStore;
import com.lawrence.travel.infrastructure.travel.TravelJson;
import com.lawrence.travel.infrastructure.travel.TravelMapper;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/** 唯一协商装配和投影组合；没有框架后台会话或第二套调度。 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class NegotiationConfiguration {
    /**
     * @param json 已有规范 JSON
     * @return 协商编码端口
     */
    @Bean InteractionCodec interactionCodec(TravelJson json) { return new InteractionJson(json); }
    /**
     * @param mapper 协商 SQL
     * @param tasks 任务 SQL
     * @param engine 执行 SQL
     * @param json 规范 JSON
     * @return 收件箱及阶段投影
     */
    @Bean NegotiationStore negotiationStore(NegotiationMapper mapper, TravelMapper tasks, EngineMapper engine, TravelJson json) { return new PostgresNegotiationStore(mapper, tasks, engine, json); }
    /**
     * @param runtime Harness
     * @param json 编解码
     * @param controls 真实调用开关
     * @return 默认不可真实调用的文本解释
     */
    @Bean InputInterpreter inputInterpreter(AgentRuntimePort runtime, InteractionCodec json, ExecutionControlProperties controls) { return new NegotiationInterpreter(runtime, json, controls.externalCallsEnabled()); }
    /**
     * @param store 协商上下文
     * @param json JSON
     * @param interpreter 外部解释端口
     * @param rounds 主动追问上限
     * @return UNDERSTAND 处理器
     */
    @Bean NegotiationHandler negotiationHandler(NegotiationStore store, InteractionCodec json, InputInterpreter interpreter,
            @org.springframework.beans.factory.annotation.Value("${travel.negotiation.max-clarification-rounds:8}") int rounds) { return new NegotiationHandler(store, json, interpreter, rounds); }
    /**
     * @param store 收件箱
     * @param travel 任务用例
     * @param runs 运行用例
     * @param engine 解释队列
     * @param transactions 业务事务
     * @return 协商用例
     */
    @Bean NegotiationService negotiationService(NegotiationStore store, TravelStorageService travel, RunService runs, ExecutionEngineStore engine, BusinessTransactions transactions) { return new NegotiationService(store, travel, runs, engine, transactions); }
    /**
     * @param candidate 候选投影
     * @param negotiation 协商投影
     * @param research 字段证据投影
     * @param json 规范 JSON，用于提交前互斥检查
     * @return 两类互斥投影组合；适配器不互调
     */
    @Bean @Primary StageProjection businessStageProjection(CandidateStore candidate, NegotiationStore negotiation, com.lawrence.travel.application.port.ResearchStore research, TravelJson json) {
        var delegates = List.of(candidate, negotiation, research);
        return new StageProjection() {
            /**
             * {@inheritDoc} 每个业务只取得自己需要的前置锁。
             */
            @Override public void beforeTaskLock(ExecutionFence fence, StageOutput output) { delegates.forEach(port -> port.beforeTaskLock(fence, output)); }
            /**
             * {@inheritDoc} 一个阶段不能同时投影候选和协商状态。
             */
            @Override public Result project(ExecutionFence fence, StageType type, StageOutput output) {
                var envelope = json.decode(output.checkpoint(), java.util.Map.class);
                // 必须在任何投影写入之前拒绝混合信封，不能用返回 null 推断适配器没有产生副作用。
                long owners = java.util.stream.Stream.of("operation", "negotiation", "research").filter(envelope::containsKey).count();
                if (owners > 1) throw new IllegalArgumentException("AMBIGUOUS_PROJECTION");
                Result selected = null;
                for (StageProjection port : delegates) { var result = port.project(fence, type, output); if (result != null) { if (selected != null) throw new IllegalArgumentException("AMBIGUOUS_PROJECTION"); selected = result; } }
                return selected;
            }
        };
    }
}
