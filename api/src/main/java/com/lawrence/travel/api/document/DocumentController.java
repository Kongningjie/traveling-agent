package com.lawrence.travel.api.document;
import com.lawrence.travel.application.document.DocumentService;
import com.lawrence.travel.application.document.Documents;
import com.lawrence.travel.application.document.DocumentFailure;
import com.lawrence.travel.domain.identity.UserIdentity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MultipartFile;
/** 私有附件收件、只读状态和显式控制；复杂解析不在HTTP线程执行。 */
@RestController @Profile("local") @RequestMapping("/api/v1/chats/{chat}/documents")
public final class DocumentController {
    private final DocumentService service;
    /** @param service 应用用例 */
    public DocumentController(DocumentService service) {this.service=service;}
    /** @param actor 身份 @param chat 聊天 @return 非删除附件及确定解析状态 */
    @GetMapping public List<Documents.View> list(@AuthenticationPrincipal UserIdentity actor,@PathVariable UUID chat) {return service.list(actor,chat);}
    /** @param actor 身份 @param chat 聊天 @param id 幂等上传 @param file 有界multipart原件 @return 202只代表可靠收件，不代表解析成功 @throws java.io.IOException 读取失败不回显原文 */
    @PostMapping public ResponseEntity<Documents.View> upload(@AuthenticationPrincipal UserIdentity actor,@PathVariable UUID chat,@RequestParam UUID id,@RequestParam MultipartFile file) throws java.io.IOException {
        if(file.getSize()>10485760) throw new DocumentFailure("DOCUMENT_INPUT_LIMIT");
        try(var in=file.getInputStream()) {return ResponseEntity.accepted().body(service.upload(actor,chat,id,file.getOriginalFilename(),in.readNBytes(10485761)));}
    }
    /** @param actor 身份 @param chat 聊天 @param id 原件 @return 附件下载，禁止内联／嗅探／缓存 */
    @GetMapping("/{id}/download") public ResponseEntity<byte[]> download(@AuthenticationPrincipal UserIdentity actor,@PathVariable UUID chat,@PathVariable UUID id) {return ResponseEntity.ok().header("Content-Type","application/octet-stream").header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","sandbox; default-src 'none'").header("Content-Disposition","attachment; filename=document-"+id).body(service.download(actor,chat,id));}
    /** @param actor 身份 @param chat 聊天 @param id 文档 @param action 显式STOP／RETRY／DELETE @return 操作后重新读取权威列表 */
    @PostMapping("/{id}/actions") public ResponseEntity<Void> action(@AuthenticationPrincipal UserIdentity actor,@PathVariable UUID chat,@PathVariable UUID id,@RequestBody Map<String,String> action) {service.action(actor,chat,id,action.getOrDefault("action",""));return ResponseEntity.noContent().build();}
    /** @param failure 固定失败 @return 不泄露路径及跨用户存在性的分类 */
    @ExceptionHandler(DocumentFailure.class) public ResponseEntity<Map<String,String>> failure(DocumentFailure failure) {String code=failure.getMessage();return ResponseEntity.status(code.equals("DOCUMENT_STORAGE_UNAVAILABLE")?503:code.equals("DOCUMENT_NOT_FOUND")?404:code.equals("DOCUMENT_INPUT_LIMIT")?413:code.equals("DOCUMENT_CAPACITY")?429:code.equals("DOCUMENT_IDEMPOTENCY_CONFLICT") || code.equals("DOCUMENT_STATE_CHANGED")?409:400).body(Map.of("code",code));}
    /** @param ignored multipart层提前拒绝 @return 固定413，不回显文件名／边界内容 */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class) public ResponseEntity<Map<String,String>> oversized(org.springframework.web.multipart.MaxUploadSizeExceededException ignored) {return ResponseEntity.status(413).body(Map.of("code","DOCUMENT_INPUT_LIMIT"));}
}
