package com.lawrence.travel.application.system;

import java.util.List;

public record SystemOverview(
        String application,
        String mode,
        List<String> modules,
        String agentStatus,
        String storageStatus,
        boolean externalCallsEnabled) {
}
