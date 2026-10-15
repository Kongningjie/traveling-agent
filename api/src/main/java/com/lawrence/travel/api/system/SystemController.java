package com.lawrence.travel.api.system;

import com.lawrence.travel.application.system.SystemOverview;
import com.lawrence.travel.application.system.SystemOverviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {
    private final SystemOverviewService service;

    public SystemController(SystemOverviewService service) {
        this.service = service;
    }

    @GetMapping("/info")
    public SystemInfoResponse info() {
        SystemOverview overview = service.describe();
        return new SystemInfoResponse(overview.application(), overview.mode(), overview.modules(),
                overview.agentStatus(), overview.storageStatus(), overview.externalCallsEnabled());
    }

    public record SystemInfoResponse(String application, String mode, List<String> modules,
                                     String agentStatus, String storageStatus, boolean externalCallsEnabled) {
    }
}
