package com.lawrence.travel.application.port;

import com.lawrence.travel.application.knowledge.Research;
import java.util.UUID;

/** 独立协调及调查上下文；所有模型调用仍经过原运行计量。 */
public interface ResearchAdvisor {
    /** @param runId 原运行 @param context 权威需求及证据 @return 受应用继续限制的调查计划 */
    Research.Plan plan(UUID runId, Research.Context context);
    /** @param runId 原运行 @param query 待核实字段 @param raw 不可信原文 @return 有连续原文支持的提取或明确空值 */
    Research.Extraction extract(UUID runId, Research.Query query, TravelToolPort.Evidence raw);
}
