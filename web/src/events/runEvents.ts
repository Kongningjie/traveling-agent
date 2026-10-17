/** 与后端任务视图一致；行程及需求保持只读，事件不能接受版本或伪造业务事实。 */
export interface TaskView {
  taskId: string
  constraintRevision: number
  generation: number
  currentVersionId: string | null
  runId: string | null
  state: string | null
  pendingInput: boolean
  cursor: string
  requirements: Record<string, unknown>
  itinerary: Record<string, unknown> | null
}

/** 只含安全进度；sequence 使用文本，浏览器不经浮点数处理事件水位。 */
export interface RunEvent {
  id: string
  sequence: string
  runId: string
  generation: number
  type: string
  sourceType: string
  state: string | null
}

/** 从复合游标取精确序号；跨任务、非规范十进制及数据库 long 溢出一律拒绝。 */
export function cursorSequence(task: string, cursor: string): bigint {
  const prefix = `${task}:`
  if (!cursor.startsWith(prefix)) throw new Error('INVALID_CURSOR')
  const value = cursor.slice(prefix.length)
  if (!/^(0|[1-9][0-9]{0,18})$/.test(value) || BigInt(value) > 9223372036854775807n) throw new Error('INVALID_CURSOR')
  return BigInt(value)
}

/** 检查不可信网络事件，拒绝错误归属、状态或序号；异常不携带响应原文。 */
export function decodeEvent(task: string, text: string, transportId: string): RunEvent {
  try {
    const event = JSON.parse(text) as RunEvent
    const sequence = cursorSequence(task, event.id)
    const states = ['QUEUED', 'RUNNING', 'WAITING_USER', 'WAITING_ACCEPTANCE', 'PAUSED_BUDGET', 'PAUSED_QUOTA', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'SUPERSEDED']
    if (event.id !== transportId || sequence.toString() !== event.sequence || sequence < 1n
      || !Number.isSafeInteger(event.generation) || event.generation < 1 || typeof event.runId !== 'string'
      || !/^[0-9a-f-]{36}$/.test(event.runId) || !/^[A-Z_]{1,48}$/.test(event.type)
      || !/^[A-Z_]{1,48}$/.test(event.sourceType) || (event.state !== null && !states.includes(event.state))) throw new Error()
    return event
  } catch { throw new Error('INVALID_EVENT') }
}

/** 去重并检查连续性；旧运行事件推进传输水位但不覆盖当前运行，新代次需要重新读取一致视图。 */
export function applyEvent(view: TaskView, lastId: string, event: RunEvent): { view: TaskView; cursor: string; applied: boolean } {
  const next = cursorSequence(view.taskId, event.id)
  const previous = cursorSequence(view.taskId, lastId)
  if (next <= previous) return { view, cursor: lastId, applied: false }
  if (next !== previous + 1n) throw new Error('EVENT_GAP')
  const changesCurrent = next > cursorSequence(view.taskId, view.cursor) && event.generation === view.generation && event.runId === view.runId
  return { view: changesCurrent && event.state !== null ? { ...view, state: event.state } : view, cursor: event.id, applied: true }
}
