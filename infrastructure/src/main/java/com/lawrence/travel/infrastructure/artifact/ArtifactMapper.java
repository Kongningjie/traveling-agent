package com.lawrence.travel.infrastructure.artifact;

import com.lawrence.travel.application.port.ArtifactStore;
import com.lawrence.travel.domain.travel.TaskScope;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 私有产物SQL；所有读取绑定归属，回收与文件登记使用相同事务锁。 */
public interface ArtifactMapper {
    /** @param scope 私有归属 @param run 运行 @return 归属匹配才存在；锁避免并发删除 */
    @Select("SELECT true FROM execution_run WHERE user_id=#{scope.userId} AND task_id=#{scope.taskId} AND id=#{run} FOR KEY SHARE")
    Boolean run(@Param("scope") TaskScope scope, @Param("run") UUID run);
    /** @param exclusive true为回收排他，否则登记共享；必须在事务中调用 @return PostgreSQL void */
    @Select("SELECT CASE WHEN #{exclusive} THEN pg_advisory_xact_lock(220022) ELSE pg_advisory_xact_lock_shared(220022) END")
    String lock(@Param("exclusive") boolean exclusive);
    /** @param e 完整不可变引用 @return 插入行数 */
    @Insert("INSERT INTO retained_artifact(id,user_id,task_id,run_id,kind,media_type,hash,size,file_key,body,visible) VALUES(#{e.id},#{e.scope.userId},#{e.scope.taskId},#{e.runId},#{e.kind},#{e.mediaType},#{e.hash},#{e.size},#{e.fileKey},#{e.body},#{e.visible})")
    int insert(@Param("e") ArtifactStore.Entry e);
    /** @param user 认证身份 @param id 产物 @return 扁平行由仓储显式构造TaskScope */
    @Select("SELECT id,user_id,task_id,run_id,kind,media_type,hash,size,file_key,body,visible FROM retained_artifact WHERE user_id=#{user} AND id=#{id} AND visible")
    Row visible(@Param("user") UUID user, @Param("id") UUID id);
    /** @return 全部有效引用；调用方需先取得回收排他锁 */
    @Select("SELECT file_key FROM retained_artifact WHERE kind='FILE'")
    List<String> references();
    /** @param id 产物 @param userId 用户 @param taskId 任务 @param runId 运行 @param kind 存储类型 @param mediaType 媒体类型 @param hash 哈希 @param size 字节数 @param fileKey 文件键 @param body 数据库正文 @param visible 是否展示 */
    record Row(UUID id, UUID userId, UUID taskId, UUID runId, String kind, String mediaType, String hash, long size, String fileKey, String body, boolean visible) { }
}
