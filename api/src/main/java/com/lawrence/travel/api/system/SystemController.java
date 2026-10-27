package com.lawrence.travel.api.system;

import com.lawrence.travel.application.system.SystemOverview;
import com.lawrence.travel.application.system.SystemOverviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 将应用层骨架信息映射为独立 HTTP DTO，不读取用户数据或持有持久层依赖。 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemController {
    private final SystemOverviewService service;

    /**
     * 注入负责聚合工程状态的应用服务。
     * @param service 已由 bootstrap 装配的应用服务，不可为 null
     */
    public SystemController(SystemOverviewService service) {
        this.service = service;
    }

    /**
     * 返回工程接线信息；占位标识不代表依赖实时探针或业务功能验收结果。
     * @return 不含凭证、会话标识和用户数据的诊断响应
     */
    @GetMapping("/info")
    public SystemInfoResponse info() {
        SystemOverview overview = service.describe();
        return new SystemInfoResponse(
                overview.application(),
                overview.mode(),
                overview.modules(),
                overview.agentStatus(),
                overview.storageStatus(),
                overview.externalCallsEnabled());
    }

    /**
     * 系统信息 HTTP 响应，仅承载可公开的工程诊断字段。
     * @param application 应用名称
     * @param mode 当前配置的运行模式，如 local 或 smoke
     * @param modules 工程模块名称列表
     * @param agentStatus Agent 接线验证标识，不是供应商实时状态
     * @param storageStatus 存储验证占位标识，不代替健康检查
     * @param externalCallsEnabled 骨架是否启用真实外部服务调用，当前固定为 false
     */
    public record SystemInfoResponse(
            String application,
            String mode,
            List<String> modules,
            String agentStatus,
            String storageStatus,
            boolean externalCallsEnabled) {}
}
