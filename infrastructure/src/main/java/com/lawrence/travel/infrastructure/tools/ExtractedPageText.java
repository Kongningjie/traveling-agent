package com.lawrence.travel.infrastructure.tools;

import com.lawrence.travel.application.knowledge.EvidencePolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** 将提取文本中的活动Markdown链接降为可见文字；不执行脚本、不解析HTML，剩余内容仍须过安全门禁。 */
final class ExtractedPageText {
    private static final Pattern ACTIVE_TARGET =
            Pattern.compile("(?is)\\s*<?(?:javascript\\s*:|vbscript\\s*:|data\\s*:\\s*text/html)");

    /** 仅提供确定性文本处理，不持有网页或用户状态。 */
    private ExtractedPageText() {}

    /**
     * 只清理结果页raw_content，不改URL、费用、日期、供应商用量或其他字段；保留原文哈希与变换标记。
     *
     * @param data 内存中的Tavily响应
     * @return 未变化时返回原对象，变化时返回含来源变换标记的新对象
     */
    static Map<String, Object> clean(Map<String, Object> data) {
        if (!(data.get("results") instanceof java.util.List<?> results)) return data;
        var pages = new ArrayList<Object>();
        boolean changed = false;
        for (Object result : results) {
            if (!(result instanceof Map<?, ?> page)
                    || !(page.get("raw_content") instanceof String original)) {
                pages.add(result);
                continue;
            }
            String text = visibleText(original);
            if (text.equals(original)) {
                pages.add(result);
                continue;
            }
            var normalized = new LinkedHashMap<Object, Object>(page);
            normalized.put("raw_content", text);
            normalized.put("raw_content_transform", "ACTIVE_MARKDOWN_TARGETS_REMOVED_V2");
            normalized.put("raw_content_original_hash", EvidencePolicy.hash(original));
            pages.add(normalized);
            changed = true;
        }
        if (!changed) return data;
        var normalized = new LinkedHashMap<>(data);
        normalized.put("results", pages);
        return normalized;
    }

    /**
     * 识别行内链接和嵌套标签，仅移除活动目标；过深或不完整语法原样保留，交给后续检查拒绝。
     *
     * @param text 网页提取文本，不包含用户会话
     * @return 可见文字和未修改的其他内容，不代表安全检查已通过
     */
    static String visibleText(String text) {
        var output = new StringBuilder(text.length());
        var labels = new java.util.ArrayDeque<Integer>();
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == '\\' && i + 1 < text.length()) {
                output.append(current).append(text.charAt(++i));
                continue;
            }
            if (current == '[') {
                if (labels.size() >= 32) return text;
                labels.push(output.length());
            } else if (current == ']' && !labels.isEmpty()) {
                int outputStart = labels.pop();
                if (i + 1 < text.length() && text.charAt(i + 1) == '(') {
                    int end = targetEnd(text, i + 1);
                    if (end < 0) {
                        output.append(text, i, text.length());
                        break;
                    }
                    if (ACTIVE_TARGET.matcher(text).region(i + 2, text.length()).lookingAt()) {
                        // 内层目标可能已经移除；只删当前左括号，不从原文恢复已清理内容。
                        output.deleteCharAt(outputStart);
                    } else output.append(text, i, end + 1);
                    i = end;
                } else output.append(current);
                continue;
            }
            output.append(current);
        }
        return output.toString();
    }

    /**
     * 找到链接目标的成对结束括号；支持转义与引号，层级超过32或未闭合时不尝试修复。
     *
     * @param text 原提取文本
     * @param start 链接目标起始左括号
     * @return 结束下标，或-1保留原文供安全检查拒绝
     */
    private static int targetEnd(String text, int start) {
        int depth = 0;
        char quote = 0;
        for (int i = start; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == '\\') {
                i++;
                continue;
            }
            if (quote != 0) {
                if (current == quote) quote = 0;
                continue;
            }
            if (current == '\'' || current == '"') {
                quote = current;
                continue;
            }
            if (current == '(' && ++depth > 32) return -1;
            if (current == ')' && --depth == 0) return i;
        }
        return -1;
    }
}
