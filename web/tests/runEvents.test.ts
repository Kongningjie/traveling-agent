import { test } from 'node:test'
import assert from 'node:assert/strict'
import { applyEvent, cursorSequence, decodeEvent, type RunEvent, type TaskView } from '../src/events/runEvents.ts'

const task = '00000000-0000-0000-0000-000000000001'
const run = '00000000-0000-0000-0000-000000000002'
const view: TaskView = { taskId: task, constraintRevision: 1, generation: 2, currentVersionId: null, runId: run, state: 'RUNNING', pendingInput: false, cursor: `${task}:1`, requirements: {}, itinerary: null }

/** 构造本地事件，不使用真实会话或供应商。 */
function event(sequence: string, generation = 2): RunEvent {
  return { id: `${task}:${sequence}`, sequence, runId: run, generation, type: 'RUN_STATUS', sourceType: 'RUN_QUEUED', state: 'QUEUED' }
}

test('复合游标保持大整数精度并拒绝其他任务及非法值', () => {
  assert.equal(cursorSequence(task, `${task}:9007199254740993`), 9007199254740993n)
  for (const cursor of [`${run}:1`, `${task}:01`, `${task}:-1`, `${task}:9223372036854775808`]) assert.throws(() => cursorSequence(task, cursor))
})
test('重复事件不重复显示，缺口不能直接推进水位', () => {
  const first = applyEvent(view, `${task}:1`, event('2'))
  assert.equal(first.cursor, `${task}:2`)
  assert.equal(applyEvent(first.view, first.cursor, event('2')).applied, false)
  assert.throws(() => applyEvent(view, `${task}:1`, event('3')), /EVENT_GAP/)
})
test('旧代次和其他运行仅供诊断，不覆盖当前进度', () => {
  assert.equal(applyEvent(view, `${task}:1`, event('2', 1)).view.state, 'RUNNING')
  assert.equal(applyEvent(view, `${task}:1`, { ...event('2'), runId: task }).view.state, 'RUNNING')
})
test('已读取较新快照时，补读的旧状态不能覆盖该快照', () => {
  const newer = { ...view, cursor: `${task}:5`, state: 'SUCCEEDED' }
  assert.equal(applyEvent(newer, `${task}:1`, event('2')).view.state, 'SUCCEEDED')
})
test('不可信事件必须匹配传输 ID、任务及规范状态', () => {
  assert.equal(decodeEvent(task, JSON.stringify(event('2')), `${task}:2`).sequence, '2')
  assert.throws(() => decodeEvent(task, JSON.stringify(event('2')), `${task}:3`), /INVALID_EVENT/)
  assert.throws(() => decodeEvent(task, JSON.stringify({ ...event('2'), state: '<script>' }), `${task}:2`), /INVALID_EVENT/)
})
