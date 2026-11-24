/* global globalThis */
import { describe, it, expect, vi, beforeEach } from 'vitest'

// mock request 模块（postSettlement 依赖它的 post 方法）
vi.mock('@/utils/request', () => ({
  default: { post: vi.fn() },
}))

import request from '@/utils/request'
import { postSettlement, settlementState } from '@/utils/settlement'

// mock sessionStorage：让 auth.getToken() 返回一个固定 JWT（payload = {"id":123}）
const JWT = 'header.eyJpZCI6MTIzfQ.signature'
globalThis.sessionStorage = {
  getItem: (k) => (k === 'token' ? JWT : null),
  setItem: () => {},
  removeItem: () => {},
}

// mock localStorage（幂等键存储）
const localStore = {}
globalThis.localStorage = {
  getItem: (k) => (k in localStore ? localStore[k] : null),
  setItem: (k, v) => { localStore[k] = String(v) },
  removeItem: (k) => { delete localStore[k] },
}

// mock window.crypto.getRandomValues：填入确定字节，便于断言幂等键
globalThis.window = {
  crypto: {
    getRandomValues: (arr) => { arr.fill(0xab); return arr },
  },
}

const REQUEST_ID = 'ab'.repeat(16) // 16 字节 0xab → 32 个 'ab'

beforeEach(() => {
  request.post.mockReset()
  Object.keys(localStore).forEach((k) => delete localStore[k])
})

describe('settlementState 结算状态映射', () => {
  it('已知状态映射为中文文案', () => {
    expect(settlementState('MULTISIG')).toBe('等待多签审批')
    expect(settlementState('PENDING')).toBe('等待处理')
    expect(settlementState('PROCESSING')).toBe('正在处理')
    expect(settlementState('RETRY')).toBe('等待重试')
    expect(settlementState('SUCCEEDED')).toBe('已完成')
    expect(settlementState('REVIEW')).toBe('需管理员核查')
  })

  it('未知状态回退为原值', () => {
    expect(settlementState('UNKNOWN')).toBe('UNKNOWN')
    expect(settlementState(undefined)).toBe(undefined)
    expect(settlementState(null)).toBe(null)
    expect(settlementState('')).toBe('')
  })
})

describe('postSettlement 幂等键', () => {
  it('首次请求生成幂等键并以 Idempotency-Key 头发出', async () => {
    request.post.mockResolvedValue({ code: 1 })
    await postSettlement('/settlements/1/retry', { amount: 10 })
    expect(request.post).toHaveBeenCalledTimes(1)
    const [url, , opts] = request.post.mock.calls[0]
    expect(url).toBe('/settlements/1/retry')
    expect(opts.headers['Idempotency-Key']).toBe(REQUEST_ID)
  })

  it('成功响应后清除本地幂等键', async () => {
    request.post.mockResolvedValue({ code: 1 })
    await postSettlement('/settlements/1/retry', { amount: 10 })
    expect(localStore['timebank:pending:123:/settlements/1/retry']).toBeUndefined()
  })

  it('失败后同参数重试复用同一幂等键', async () => {
    request.post.mockRejectedValueOnce({ response: { data: { msg: '网络错误' } } })
    await expect(postSettlement('/x', { amount: 10 })).rejects.toThrow('网络错误')
    // 幂等键被保留
    const saved = JSON.parse(localStore['timebank:pending:123:/x'])
    expect(saved.requestId).toBe(REQUEST_ID)

    // 同参数重试：复用原 requestId，而非重新生成
    request.post.mockResolvedValueOnce({ code: 1 })
    await postSettlement('/x', { amount: 10 })
    const retryKey = request.post.mock.calls[1][2].headers['Idempotency-Key']
    expect(retryKey).toBe(saved.requestId)
  })

  it('不同参数拒绝提交，防止覆盖未核对结果', async () => {
    request.post.mockRejectedValueOnce({ response: { data: { msg: '网络错误' } } })
    await expect(postSettlement('/x', { amount: 10 })).rejects.toThrow('网络错误')
    await expect(postSettlement('/x', { amount: 20 })).rejects.toThrow('上次请求结果待核对')
    expect(request.post).toHaveBeenCalledTimes(1) // 第二次未发出
  })

  it('password 不参与幂等指纹（不保存密码）', async () => {
    request.post.mockRejectedValueOnce({ response: { data: { msg: '网络错误' } } })
    await expect(postSettlement('/x', { amount: 10, password: 'p1' })).rejects.toThrow('网络错误')
    // 密码改变但业务参数相同：不应被拒绝，且复用幂等键
    request.post.mockResolvedValueOnce({ code: 1 })
    await postSettlement('/x', { amount: 10, password: 'p2' })
    const key = request.post.mock.calls[1][2].headers['Idempotency-Key']
    expect(key).toBe(REQUEST_ID)
  })

  it('无响应消息时给出兜底提示并保留原编号', async () => {
    request.post.mockRejectedValueOnce(new Error('network down'))
    await expect(postSettlement('/x', { amount: 10 })).rejects.toThrow('未收到请求结果，原编号已保留')
    // 幂等键仍保留，供重试
    expect(localStore['timebank:pending:123:/x']).not.toBeUndefined()
  })
})
