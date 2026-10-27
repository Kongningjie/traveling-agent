package com.lawrence.travel.application.document;

import java.util.List;
import java.util.UUID;

/** 附件协议；不携带宿主路径，原文不是业务授权。 */
public final class Documents {
    /** 命名空间。 */
    private Documents() {}

    /**
     * @param id 不可变原件版本
     * @param userId 所有者
     * @param chatId 聊天
     * @param jobId 解析作业
     * @param name 展示名
     * @param kind 白名单扩展
     * @param hash SHA256
     * @param size 原件字节
     * @param key 服务端存储键
     * @param deleted 是否撤销读取
     * @param report PG解析报告
     */
    public record Entry(
            UUID id,
            UUID userId,
            UUID chatId,
            UUID jobId,
            String name,
            String kind,
            String hash,
            long size,
            String key,
            boolean deleted,
            String report) {}

    /**
     * @param position 页／段序号，从1开始
     * @param text 完整有界文本
     */
    public record Section(int position, String text) {}

    /**
     * @param parserVersion 固定解析器版本
     * @param code OK或固定拒绝分类
     * @param sections 实际提取内容
     * @param notice 限制说明，不能假称OCR或表格完整
     */
    public record Report(String parserVersion, String code, List<Section> sections, String notice) {
        /** 防止提交期间修改正文。 */
        public Report {
            sections = List.copyOf(sections);
        }
    }

    /**
     * @param id 文档
     * @param name 显示名
     * @param kind 类型
     * @param size 字节
     * @param state 用户状态
     * @param code 固定结果
     * @param sections 实际页／段数
     * @param notice 解析限制
     */
    public record View(
            UUID id,
            String name,
            String kind,
            long size,
            String state,
            String code,
            int sections,
            String notice) {}

    /**
     * @param job 作业
     * @param token 尝试围栏
     * @param key 受管原件
     * @param kind 类型
     * @param hash 原件校验
     * @param nonce 单次请求
     * @param timestamp 秒级签发时间
     */
    public record Request(
            UUID job,
            long token,
            String key,
            String kind,
            String hash,
            UUID nonce,
            long timestamp) {}
}
