package com.lawrence.travel.application.port;

/** 部署容量准入；不决定用户身份/业务权限，不阻塞读/停/删。 */
@FunctionalInterface
public interface StorageAdmission {
    /**
     * @return 是否可以接纳新的正文/附件和后台工作
     */
    boolean accepting();
}
