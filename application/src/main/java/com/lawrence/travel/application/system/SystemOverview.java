package com.lawrence.travel.application.system;

import java.util.List;

/**
 * 应用层工程信息快照，供 API 映射；不包含真实用户数据或框架内部对象。
 *
 * @param application 应用名称
 * @param mode 当前运行配置标识
 * @param modules 工程模块名称列表，由调用方避免修改
 * @param agentStatus 适配器声明的接线验证标识
 * @param storageStatus 当前存储验证占位标识，不是实时连通性判断
 * @param externalCallsEnabled 是否启用外部服务请求，骨架阶段固定为 false
 */
public record SystemOverview(
        String application,
        String mode,
        List<String> modules,
        String agentStatus,
        String storageStatus,
        boolean externalCallsEnabled) {
}
