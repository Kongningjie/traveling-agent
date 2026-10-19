package com.lawrence.travel.application.port;

import com.lawrence.travel.application.document.SourceRead;
import java.util.List;
import java.util.UUID;

/** 资料权限、水位、引用和卸载正文的PG权威端口；所有方法在应用短事务执行。 */
public interface SourceReadStore {
    /** @param user 登录用户 @param chat 私有聊天 @return 锁定用户与聊天后的读取范围 */
    SourceRead.Scope browser(UUID user, UUID chat);
    /** @param budget 原输入预算 @param token 调用绑定的原尝试 @return 有效聊天作业范围，不能重读新token为旧调用授权 */
    SourceRead.Scope agent(UUID budget, long token);
    /** @param scope 受信范围 @param id 引用 @param document 可空原件 @param body 有界正文JSON或卸载文本 @param path 可空逻辑路径；同尝试同路径内容不可变 */
    void save(SourceRead.Scope scope, UUID id, UUID document, String body, String path);
    /** @param scope 当前私有范围 @param id 引用 @return 当前仍有效且哈希一致的正文 */
    String read(SourceRead.Scope scope, UUID id);
    /** @param scope 当前原尝试 @param path 严格逻辑路径 @return 有效卸载正文 */
    String file(SourceRead.Scope scope, String path);
    /** @param scope 当前范围 @param query 字面检索词 @return 最多5条截止本轮之前且偏好仍有效的历史 */
    List<SourceRead.Conversation> history(SourceRead.Scope scope, String query);
    /** @param scope 私有范围 @param message 原输入 @return 成功回复实际读取的最多10个引用ID，失败尝试不展示 */
    List<UUID> references(SourceRead.Scope scope, UUID message);
    /** @param scope 当前有效尝试 @return 当前或成功历史尝试的最多10个可恢复工作文件；不含全文 */
    List<SourceRead.WorkFile> files(SourceRead.Scope scope);
    /** @param scope 当前有效尝试 @param path 有效工作文件 @return 原文件写入尝试实际读取的最多10个资料片段，供传播依赖 */
    List<String> fileReferences(SourceRead.Scope scope, String path);
}
