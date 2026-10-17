/** 固定HTTP错误，不保存或展示服务端原文、请求体或凭证。 */
export class ApiError extends Error {
  readonly status: number;
  /** @param status HTTP状态，0表示网络结果不明，不自动重发写请求。 */
  constructor(status: number) {
    super(
      status === 409
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
      throw new ApiError(0);
    }
  }
  /** @param path 同源GET @param signal 页面切换取消读取 @returns 私有只读结果 */
  get<T>(path: string, signal?: AbortSignal): Promise<T> {
    return this.request<T>(path, { signal });
  }
  /** @param path 业务POST @param body 固定命令，调用方负责幂等键 @returns 首次或同请求重放结果；绝不自动重试 */
  async post<T>(path: string, body: unknown): Promise<T> {
    const csrf = await this.get<{ headerName: string; token: string }>(
      "/api/v1/auth/csrf",
    );
    return this.request<T>(path, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        [csrf.headerName]: csrf.token,
      },
      body: JSON.stringify(body),
    });
  }
}
