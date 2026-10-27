package com.lawrence.travel.application.identity;

import com.lawrence.travel.domain.identity.UserIdentity;
import com.lawrence.travel.domain.travel.TaskScope;
import static com.lawrence.travel.application.identity.IdentityFailure.Code.NOT_FOUND;

/** 私有资源归属用例；资源值须由受信仓储读取，不能使用客户端自报归属。 */
public final class OwnershipGuard {
    /** 无状态守卫，不持有浏览器会话或可变身份。 */
    public OwnershipGuard() {}

    /**
     * 非本人资源统一视作不存在，不泄露资源存在性。
     * @param actor 当前服务端认证且已检查状态的用户
     * @param resource 仓储读取的归属，资源不存在时为空
     */
    public void requireOwner(UserIdentity actor, TaskScope resource) {
        if (actor == null || resource == null || !actor.id().equals(resource.userId()))
            throw new IdentityFailure(NOT_FOUND);
    }

    /**
     * 关联资源必须同用户、同任务，不能只检查父对象。
     * @param actor 认证用户
     * @param parent 父资源归属
     * @param related 被引用资源归属
     */
    public void requireRelated(UserIdentity actor, TaskScope parent, TaskScope related) {
        requireOwner(actor, parent);
        requireOwner(actor, related);
        if (!parent.equals(related)) throw new IdentityFailure(NOT_FOUND);
    }
}
