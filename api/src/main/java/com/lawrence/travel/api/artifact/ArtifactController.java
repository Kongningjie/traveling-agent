package com.lawrence.travel.api.artifact;

import com.lawrence.travel.application.artifact.ArtifactFailure;
import com.lawrence.travel.application.artifact.ArtifactService;
import com.lawrence.travel.application.travel.TravelStorageFailure;
import com.lawrence.travel.domain.identity.UserIdentity;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 有界鉴权下载；禁止公开目录、任意路径、上传和框架文件读取。 */
@RestController
@Profile("local")
public final class ArtifactController {
    private final ArtifactService service;

    /**
     * @param service 产物用例，Controller不接触Mapper或文件系统
     */
    public ArtifactController(ArtifactService service) {
        this.service = service;
    }

    /**
     * @param actor 会话身份
     * @param id 产物身份
     * @return 完整性检查后的附件下载；不可缓存，不内联执行HTML或JSON
     */
    @GetMapping("/api/v1/artifacts/{id}")
    public ResponseEntity<byte[]> get(
            @AuthenticationPrincipal UserIdentity actor, @PathVariable UUID id) {
        var content = service.read(actor, id);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "attachment; filename=artifact-" + id)
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .header("Content-Type", content.mediaType())
                .body(content.bytes());
    }

    /**
     * @param failure 固定完整性分类
     * @return 明确缺失或损坏，绝不回显文件路径
     */
    @ExceptionHandler(ArtifactFailure.class)
    public ResponseEntity<Map<String, String>> corrupt(ArtifactFailure failure) {
        return ResponseEntity.status(503).body(Map.of("code", failure.getMessage()));
    }

    /**
     * @param ignored 越权与缺失
     * @return 相同404，避免资源存在性泄露
     */
    @ExceptionHandler(TravelStorageFailure.class)
    public ResponseEntity<Map<String, String>> invisible(TravelStorageFailure ignored) {
        return ResponseEntity.status(404).body(Map.of("code", "NOT_FOUND"));
    }
}
