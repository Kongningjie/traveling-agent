package com.lawrence.travel.infrastructure.identity;

import com.lawrence.travel.application.port.AccountStore;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 明确账号 SQL，登录唯一性由 PostgreSQL 裁定，不先查后写。 */
public interface AccountMapper {
    /**
     * 原子插入账号，登录名竞争不抛出带参数的异常。
     * @param id 服务端账号 ID
     * @param name 规范名
     * @param hash 自适应哈希
     * @return 插入行数
     */
    @Insert("INSERT INTO app_user(id,login_name,password_hash) VALUES(#{id},#{name},#{hash}) ON CONFLICT(login_name) DO NOTHING")
    int insert(@Param("id") UUID id, @Param("name") String name, @Param("hash") String hash);
    /**
     * 仅认证路径读取凭据。
     * @param name 规范名
     * @return 内部账号或空
     */
    @Select("SELECT id,login_name,password_hash,(account_status='ACTIVE') AS enabled,preference_revision FROM app_user WHERE login_name=#{name}")
    AccountStore.Account findByName(@Param("name") String name);
    /**
     * 当前身份与停用状态检查。
     * @param id 受信主体 ID
     * @return 内部账号或空
     */
    @Select("SELECT id,login_name,password_hash,(account_status='ACTIVE') AS enabled,preference_revision FROM app_user WHERE id=#{id}")
    AccountStore.Account findById(@Param("id") UUID id);
}
