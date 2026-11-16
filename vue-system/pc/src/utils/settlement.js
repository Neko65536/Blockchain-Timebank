import request from './request';
import { getToken } from './auth';

// 不保存密码。网络结果不明时保留原编号，刷新页面后仍可重试同一请求。
export async function postSettlement(url, payload) {
  const claims = JSON.parse(atob(getToken().split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
  const storageKey = 'timebank:pending:' + claims.id + ':' + url;
  const business = { ...payload };
  delete business.password;
  const fingerprint = JSON.stringify(business);
  const saved = JSON.parse(localStorage.getItem(storageKey) || 'null');
  if (saved && saved.fingerprint !== fingerprint) {
    return Promise.reject(new Error('上次请求结果待核对，请保持原参数重试，或先在结算记录中核查'));
  }
  const bytes = new Uint8Array(16);
  window.crypto.getRandomValues(bytes);
  const requestId = saved ? saved.requestId : Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
  localStorage.setItem(storageKey, JSON.stringify({ requestId, fingerprint }));
  return request.post(url, payload, { timeout: 10000, headers: { 'Idempotency-Key': requestId } })
    .then(response => {
      localStorage.removeItem(storageKey);
      return response;
    }).catch(error => {
      const message = error.response && error.response.data && error.response.data.msg;
      throw new Error(message || '未收到请求结果，原编号已保留；请保持原参数重试并查询结算记录');
    });
}

export function settlementState(state) {
  return ({ PENDING: '等待处理', PROCESSING: '正在处理', RETRY: '等待重试', SUCCEEDED: '已完成', REVIEW: '需管理员核查' })[state] || state;
}
