/* global globalThis */
import { describe, it, expect, beforeEach } from 'vitest'
import { getToken, setToken, removeToken } from '@/utils/auth'

// Vitest 默认 node 环境无 sessionStorage，用内存 mock 模拟浏览器会话存储。
const store = {}
globalThis.sessionStorage = {
  getItem: (k) => (k in store ? store[k] : null),
  setItem: (k, v) => { store[k] = String(v) },
  removeItem: (k) => { delete store[k] },
}

beforeEach(() => {
  Object.keys(store).forEach((k) => delete store[k])
})

describe('pc 端 auth token 会话存储', () => {
  it('setToken/getToken 往返一致', () => {
    setToken('pc-token-123')
    expect(getToken()).toBe('pc-token-123')
  })

  it('getToken 无值时返回 null', () => {
    expect(getToken()).toBeNull()
  })

  it('removeToken 清除 token', () => {
    setToken('abc')
    removeToken()
    expect(getToken()).toBeNull()
  })
})
