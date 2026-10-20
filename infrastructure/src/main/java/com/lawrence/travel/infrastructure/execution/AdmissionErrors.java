package com.lawrence.travel.infrastructure.execution;

/** 只按专用SQLState分类，不匹配/输出数据库原始错误正文。 */
public final class AdmissionErrors {
    /** 工具命名空间。 */
    private AdmissionErrors() {}

    /**
     * @param error 基础设施异常链；SQLState可信且固定，其他错误仍由原适配器脱敏
     */
    public static void rethrowKnown(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause())
            if (e instanceof java.sql.SQLException sql && "P1001".equals(sql.getSQLState()))
                throw new com.lawrence.travel.application.execution.AdmissionFailure();
    }
}
