/* global globalThis */
import { describe, it, expect, beforeEach } from 'vitest'
import {
  getToken, setToken, removeToken,
  getRefreshToken, setRefreshToken, removeRefreshToken,
} from '@/utils/auth'

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

describe('auth token 会话存储', () => {
  it('setToken/getToken 往返一致', () => {
    setToken('abc123')
    expect(getToken()).toBe('abc123')
  })

  it('getToken 无值时返回 null', () => {
    expect(getToken()).toBeNull()
  })

  it('setRefreshToken/getRefreshToken 往返一致', () => {
    setRefreshToken('refresh-token-xyz')
    expect(getRefreshToken()).toBe('refresh-token-xyz')
  })

  it('setRefreshToken 空值不写入', () => {
    setRefreshToken('')
    expect(getRefreshToken()).toBeNull()
  })

  it('removeToken 同时清除 access 与 refresh', () => {
    setToken('a')
    setRefreshToken('r')
    removeToken()
    expect(getToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
  })

  it('removeRefreshToken 仅清除 refresh，不影响 access', () => {
    setToken('a')
    setRefreshToken('r')
    removeRefreshToken()
    expect(getRefreshToken()).toBeNull()
    expect(getToken()).toBe('a')
  })
})
