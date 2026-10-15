package com.lawrence.travel.application.system;

import com.lawrence.travel.application.port.AgentRuntimePort;

import java.util.List;

public final class SystemOverviewService {
    private final AgentRuntimePort agentRuntime;
    private final String mode;

    public SystemOverviewService(AgentRuntimePort agentRuntime, String mode) {
        this.agentRuntime = agentRuntime;
        this.mode = mode;
    }

    public SystemOverview describe() {
        return new SystemOverview(
                "traveling-agent",
                mode,
                List.of("api", "application", "domain", "agent", "infrastructure", "bootstrap", "web"),
                agentRuntime.integrationStatus(),
                "NOT_VERIFIED",
                false);
    }
}
