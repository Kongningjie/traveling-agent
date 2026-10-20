import type { User } from "./types";

/** 固定HTTP错误，不保存或展示服务端原文、请求体或凭证。 */
export class ApiError extends Error {
  readonly status: number;
  /** @param status HTTP状态，0表示网络结果不明，不自动重发写请求。 */
  constructor(status: number) {
    super(
      status === 429
        ? "当前容量已达上限，请等待或停止部分任务；仍可读取和删除。"
        : status === 409
        ? "状态已更新，请刷新后重新确认。"
        : status === 401 || status === 403
          ? "登录已失效或请求被拒绝，请重新登录。"
          : status === 404
            ? "任务或版本不可见。"
            : status === 400
              ? "输入无效，请检查日期、人数及提交范围。"
              : status === 0
                ? "网络中断，操作结果尚不确定，可重试原操作。"
                : "服务暂时不可用，请稍后重试。",
    );
    this.status = status;
  }
}
/**
 * 校验IdentityController返回的最小身份，不能依赖泛型强制转换掩盖字段差异。
 * @param value 未验证的登录或恢复会话响应；不包含可供前端持久化的凭证。
 * @returns 仅保留id和loginName；身份不完整时在渲染前拒绝，不返回占位登录身份。
 * @throws ApiError 结构与UserIdentity契约不符时使用固定502说明，禁止附带原始响应。
 */
export function decodeUser(value: unknown): User {
  if (!value || typeof value !== "object" || Array.isArray(value))
    throw new ApiError(502);
  const data = value as Record<string, unknown>;
  if (
    typeof data.id !== "string" ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
      data.id,
    ) ||
    typeof data.loginName !== "string" ||
    !/^[a-z0-9_]{3,32}$/.test(data.loginName)
  )
    throw new ApiError(502);
  return { id: data.id, loginName: data.loginName };
}
/** 同源API封装；Cookie由浏览器管理，CSRF每次写入前获取，不持久化令牌。 */
export class Api {
  /** @param path 同源接口 @param options 请求选项 @returns 类型化结果；异常不携带原文 */
  async request<T>(path: string, options: RequestInit = {}): Promise<T> {
    try {
      const response = await fetch(path, {
        ...options,
        credentials: "same-origin",
        cache: "no-store",
        signal: options.signal
          ? AbortSignal.any([options.signal, AbortSignal.timeout(15000)])
          : AbortSignal.timeout(15000),
      });
      if (!response.ok) throw new ApiError(response.status);
      if (response.status === 204) return undefined as T;
      const text = await response.text();
      return (text ? JSON.parse(text) : null) as T;
    } catch (error) {
      if (error instanceof ApiError) throw error;
      // 仅发送固定故障信号；诊断自身失败不得递归上报，也不带原始错误或路径。
      if (typeof window !== "undefined" && !path.startsWith("/api/v1/support/") && !path.startsWith("/api/v1/auth/")) window.dispatchEvent(new Event("travel:network-error"));
      throw new ApiError(0);
    }
  }
  /** @param path 同源GET @param signal 页面切换取消读取 @returns 私有只读结果 */
  get<T>(path: string, signal?: AbortSignal): Promise<T> {
    return this.request<T>(path, { signal });
  }
  /** @param path 业务POST @param body 固定命令，调用方负责幂等键 @returns 首次或同请求重放结果；绝不自动重试 */
  async post<T>(path: string, body: unknown): Promise<T> {
    return this.write<T>("POST", path, body);
  }
  /** @param method 显式业务写方法 @param path 同源私有接口 @param body 固定幂等命令 @returns 权威结果；网络不自动重试 */
  async write<T>(
    method: "POST" | "PATCH" | "DELETE",
    path: string,
    body: unknown,
  ): Promise<T> {
    const csrf = await this.get<{ headerName: string; token: string }>(
      "/api/v1/auth/csrf",
    );
    return this.request<T>(path, {
      method,
      headers: {
        "Content-Type": "application/json",
        [csrf.headerName]: csrf.token,
      },
      body: JSON.stringify(body),
    });
  }
}
