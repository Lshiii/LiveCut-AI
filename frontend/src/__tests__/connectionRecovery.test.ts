import test from 'node:test'
import assert from 'node:assert/strict'
import { DetailRecovery, isConnectionErrorCode } from '../connectionRecovery.ts'

test('a disconnected detail read can resume after a healthy refresh without changing its selection', () => {
  const recovery = new DetailRecovery()
  recovery.queue('initial-url-job', 1)
  assert.equal(recovery.next(1, true), null, 'an edit or pending operation defers recovery')
  assert.equal(recovery.next(1, false), 'initial-url-job')
  recovery.cancel()
  assert.equal(recovery.next(1, false), null, 'successful detail loading stops recovery')
})

test('a newer task selection or new analysis cannot restore the old URL task', () => {
  const recovery = new DetailRecovery()
  recovery.queue('old-task', 3)
  assert.equal(recovery.next(4, false), null)
  assert.equal(recovery.next(3, false), null, 'a stale selection cannot become active again')
  recovery.queue('old-task', 4)
  recovery.cancel()
  assert.equal(recovery.next(4, false), null, 'an explicit new-analysis action cancels recovery')
})

test('automatic detail reads are bounded and blocked refreshes do not spend attempts', () => {
  const recovery = new DetailRecovery()
  recovery.queue('task', 1)
  for (let index = 0; index < 5; index++) assert.equal(recovery.next(1, true), null)
  for (let index = 0; index < 3; index++) assert.equal(recovery.next(1, false), 'task')
  assert.equal(recovery.next(1, false), null)
  assert.equal(recovery.next(1, false), null)
  recovery.queue('explicitly-selected-task', 2)
  assert.equal(recovery.next(2, false), 'explicitly-selected-task')
})

test('only transient connection failures are recoverable; HTTP and business failures stay visible', () => {
  for (const code of ['NETWORK_UNAVAILABLE', 'REQUEST_TIMEOUT']) assert.equal(isConnectionErrorCode(code), true)
  for (const code of ['HTTP_404', 'HTTP_500', 'JOB_NOT_FOUND', 'REVIEW_REVISION_CONFLICT', 'INVALID_SERVER_RESPONSE', 'CLIENT_ERROR']) {
    assert.equal(isConnectionErrorCode(code), false, code)
  }
})
