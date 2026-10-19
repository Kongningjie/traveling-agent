package com.lawrence.travel.domain.support;

/** 问题处理状态机；重试是运行事实，不能自动充当修复或验证。 */
public enum ProblemState {
    /** 新问题。 */
    NEW,
    /** 已分类。 */
    TRIAGED,
    /** 正在处理。 */
    IN_PROGRESS,
    /** 已提供修复依据。 */
    RESOLVED,
    /** 回归验证通过。 */
    VERIFIED,
    /** 再次出现。 */
    REOPENED,
    /** 已确认重复。 */
    DUPLICATE;

    /**
     * @param next 下一状态
     * @param repair 固定修复版本或配置版本
     * @param regression 回归用例引用
     * @param passed 维护者明确确认回归通过
     * @return 是否符合状态及修复证据契约
     */
    public boolean permits(ProblemState next, String repair, String regression, boolean passed) {
        boolean edge =
                switch (this) {
                    case NEW -> next == TRIAGED || next == DUPLICATE;
                    case TRIAGED -> next == IN_PROGRESS || next == DUPLICATE;
                    case IN_PROGRESS -> next == RESOLVED;
                    case RESOLVED -> next == VERIFIED || next == REOPENED;
                    case VERIFIED -> next == REOPENED;
                    case REOPENED -> next == IN_PROGRESS || next == DUPLICATE;
                    case DUPLICATE -> next == REOPENED;
                };
        return edge
                && (next != RESOLVED && next != VERIFIED || token(repair) && token(regression))
                && (next != VERIFIED || passed);
    }

    /**
     * @param value 不允许自由正文的证据标识
     * @return 有界可定位版本/测试编号
     */
    public static boolean token(String value) {
        return value != null && value.matches("[A-Za-z0-9_.:/#-]{1,120}");
    }
}
