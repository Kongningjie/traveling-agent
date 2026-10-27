package com.lawrence.travel.application.port;

import com.lawrence.travel.application.document.SourceRead;
import java.util.List;
import java.util.UUID;

/** 资料权限、水位、引用和卸载正文的PG权威端口；所有方法在应用短事务执行。 */
public interface SourceReadStore {
    /**
     * 核对引用是否确由本预算及尝试读取，拒绝把旧结果或浏览器读取冒充本轮工具证据。
     * @param scope 当前活跃尝试
     * @param reference 片段ID
     * @return 是否确由本预算/尝试实际读取，不能把浏览器或旧回复引用冒充本轮工具结果
     */
    boolean fromAttempt(
            com.lawrence.travel.application.document.SourceRead.Scope scope, UUID reference);

    /**
     * 从登录身份建立受锁保护的浏览器读取范围，不接受前端自行指定的用户归属。
     * @param user 登录用户
     * @param chat 私有聊天
     * @return 锁定用户与聊天后的读取范围
     */
    SourceRead.Scope browser(UUID user, UUID chat);

    /**
     * 从服务器预算与原尝试token恢复Agent读取范围，作业失效后拒绝读取。
     * @param budget 原输入预算
     * @param token 调用绑定的原尝试
     * @return 有效聊天作业范围，不能重读新token为旧调用授权
     */
    SourceRead.Scope agent(UUID budget, long token);

    /**
     * 保存本尝试实际读取的有界片段或卸载正文，相同逻辑工作文件的内容保持不可变。
     * @param scope 受信范围
     * @param id 引用
     * @param document 可空原件
     * @param body 有界正文JSON或卸载文本
     * @param path 可空逻辑路径；同尝试同路径内容不可变
     */
    void save(SourceRead.Scope scope, UUID id, UUID document, String body, String path);

    /**
     * 重查引用归属、删除水位与正文哈希，只有仍有效的引用才能显示。
     * @param scope 当前私有范围
     * @param id 引用
     * @return 当前仍有效且哈希一致的正文
     */
    String read(SourceRead.Scope scope, UUID id);

    /**
     * 读取受控逻辑路径下的卸载正文，不允许任意文件系统路径。
     * @param scope 当前原尝试
     * @param path 严格逻辑路径
     * @return 有效卸载正文
     */
    String file(SourceRead.Scope scope, String path);

    /**
     * 按当前来源和偏好版本检索有界历史，不将旧会话内容重新视为授权。
     * @param scope 当前范围
     * @param query 字面检索词
     * @return 最多5条截止本轮之前且偏好仍有效的历史
     */
    List<SourceRead.Conversation> history(SourceRead.Scope scope, String query);

    /**
     * 列出成功回复真正读取的引用，失败尝试的引用不作为用户可见证据。
     * @param scope 私有范围
     * @param message 原输入
     * @return 成功回复实际读取的最多10个引用ID，失败尝试不展示
     */
    List<UUID> references(SourceRead.Scope scope, UUID message);

    /**
     * 列出当前或成功历史尝试仍可恢复的工作文件元数据，不直接注入全文。
     * @param scope 当前有效尝试
     * @return 当前或成功历史尝试的最多10个可恢复工作文件；不含全文
     */
    List<SourceRead.WorkFile> files(SourceRead.Scope scope);

    /**
     * 取得工作文件依赖的原片段，以便资料删除或变化时一并使派生内容失效。
     * @param scope 当前有效尝试
     * @param path 有效工作文件
     * @return 原文件写入尝试实际读取的最多10个资料片段，供传播依赖
     */
    List<String> fileReferences(SourceRead.Scope scope, String path);
}
