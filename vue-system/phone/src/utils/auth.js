// 在存储token时使用会话存储空间 sessionStorage
const TokenKey = 'token'
// N1：refresh token 会话内保存，供 access token 过期时换取新令牌。
const RefreshTokenKey = 'refreshToken'

export function getToken() {
  return sessionStorage.getItem(TokenKey)
}

export function setToken(token) {
  sessionStorage.setItem(TokenKey, token)
}

export function removeToken() {
  sessionStorage.removeItem(TokenKey)
  sessionStorage.removeItem(RefreshTokenKey)
}

export function getRefreshToken() {
  return sessionStorage.getItem(RefreshTokenKey)
}

export function setRefreshToken(token) {
  if (token) sessionStorage.setItem(RefreshTokenKey, token)
}

export function removeRefreshToken() {
  sessionStorage.removeItem(RefreshTokenKey)
}
