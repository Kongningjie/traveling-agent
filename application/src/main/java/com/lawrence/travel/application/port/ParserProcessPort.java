package com.lawrence.travel.application.port;

import com.lawrence.travel.application.document.Documents;

/** 无业务调度的隔离执行端口；不可用绝不在Web进程解析。 */
public interface ParserProcessPort {
    /**
     * @param request 服务端签发的尝试身份与受管键
     * @return 有界报告；取消需等待物理进程退出后返回
     */
    Documents.Report parse(Documents.Request request);
}
