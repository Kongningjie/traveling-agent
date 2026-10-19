package com.lawrence.travel.application.port;

import com.lawrence.travel.application.support.Support;

import java.util.List;
import java.util.UUID;

/** PG反馈/问题和审计端口；事务由应用控制，不取得旅行业务写权限。 */
public interface SupportStore {
    /**
     * @param user 已认证用户；同用户创建串行、计数及幂等
     */
    void lock(UUID user);

    /**
     * @param user 用户
     * @param id 请求
     * @param hash 本次正文哈希
     * @return 已存在回执，异文冲突，缺失为空
     */
    Support.Receipt replay(UUID user, UUID id, String hash);

    /**
     * @param user 用户
     * @param target 私有目标
     * @return 服务器定位原链路/步骤；不存在统一拒绝
     */
    Support.Link locate(UUID user, Support.Target target);

    /**
     * @param user 用户
     * @param input 有界反馈
     * @param hash 原请求摘要
     * @param link 精确归属
     * @return 已保存回执
     */
    Support.Receipt insert(UUID user, Support.Input input, String hash, Support.Link link);

    /**
     * @param user 用户
     * @param offset 分页
     * @return 本人反馈，不包含正文
     */
    List<Support.Feedback> feedback(UUID user, int offset);

    /**
     * @param actor 读取人
     * @param id 反馈
     * @param maintenance 是否维护者读取
     * @return 敏感正文；必须通过权限检查并记录审计
     */
    Support.Detail detail(UUID actor, UUID id, boolean maintenance);

    /**
     * @param actor 读取人
     * @param sensitive 是否要求敏感权限
     * @return 当前DB维护权限，默认无
     */
    boolean allowed(UUID actor, boolean sensitive);

    /**
     * @param actor 维护人
     * @param offset 分页
     * @return 最近聚合问题，默认不含正常取消/额度提示
     */
    List<Support.Group> groups(UUID actor, int offset);

    /**
     * @param actor 维护人
     * @param id 聚合问题
     * @return 锁定当前版本
     */
    Support.Group group(UUID actor, UUID id);

    /**
     * @param actor 维护人
     * @param id 问题
     * @param change 已校验状态及依据
     */
    void change(UUID actor, UUID id, Support.Change change);

    /**
     * @param actor 维护人
     * @param group 聚合或问题编号
     * @return 有界事件，不含正文
     */
    List<Support.Event> events(UUID actor, UUID group);

    /**
     * @param actor 维护人
     * @param trace 链路
     * @return 有界事实视图并记录敏感读取审计
     */
    Support.Trace trace(UUID actor, UUID trace);

    /**
     * @param user 登录主体
     * @param id 幂等ID
     * @param code 固定客户端故障
     * @param component 固定组件 @return 只有成功保存才返回问题编号
     */
    UUID client(UUID user, UUID id, String code, String component);

    /**
     * @return PG全局诊断容量丢弃计数，仅在维护权限检查后读取
     */
    long dropped();

    /**
     * @param user 受控身份，可空
     * @param id 固定记录ID
     * @param request 原请求
     * @param trace 原链路
     * @param code 固定降级分类
     * @return 是否实际写入
     */
    boolean diagnostic(UUID user, UUID id, UUID request, UUID trace, String code);
}
