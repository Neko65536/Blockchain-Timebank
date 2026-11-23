import { describe, it, expect } from 'vitest'
import {
  DONE_STATE, MAX_POLL_ATTEMPTS,
  isBatchDone, shouldStopPolling, buildDoneMessage,
} from '@/utils/batchProgress'

describe('批量答谢轮询纯逻辑', () => {
  it('isBatchDone 仅 DONE 为终态', () => {
    expect(isBatchDone(DONE_STATE)).toBe(true)
    expect(isBatchDone('RUNNING')).toBe(false)
    expect(isBatchDone('PREPARED')).toBe(false)
    expect(isBatchDone(null)).toBe(false)
    expect(isBatchDone(undefined)).toBe(false)
  })

  it('shouldStopPolling 达到最大次数即停止', () => {
    expect(shouldStopPolling(0)).toBe(false)
    expect(shouldStopPolling(MAX_POLL_ATTEMPTS - 1)).toBe(false)
    expect(shouldStopPolling(MAX_POLL_ATTEMPTS)).toBe(true)
    expect(shouldStopPolling(MAX_POLL_ATTEMPTS + 10)).toBe(true)
  })

  it('shouldStopPolling 支持自定义最大次数', () => {
    expect(shouldStopPolling(3, 3)).toBe(true)
    expect(shouldStopPolling(2, 3)).toBe(false)
  })

  it('buildDoneMessage 无失败时只报成功', () => {
    const r = buildDoneMessage(5, 0)
    expect(r.failed).toBe(0)
    expect(r.hasFailure).toBe(false)
    expect(r.message).toBe('批量答谢完成：成功 5 人')
  })

  it('buildDoneMessage 有失败时附带失败数', () => {
    const r = buildDoneMessage(5, 2)
    expect(r.failed).toBe(2)
    expect(r.hasFailure).toBe(true)
    expect(r.message).toBe('批量答谢完成：成功 5 人，失败 2 人')
  })

  it('buildDoneMessage 兼容后端返回 string/null', () => {
    expect(buildDoneMessage('5', '2').failed).toBe(2)
    expect(buildDoneMessage(null, null).message).toBe('批量答谢完成：成功 0 人')
    expect(buildDoneMessage(undefined, undefined).hasFailure).toBe(false)
  })
})
