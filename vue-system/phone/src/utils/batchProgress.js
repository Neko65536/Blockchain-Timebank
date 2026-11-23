// N4：批量答谢异步轮询的纯逻辑，抽离以便单元测试，避免组件内难以验证的状态判断散落。
export const DONE_STATE = 'DONE'
export const MAX_POLL_ATTEMPTS = 60
export const POLL_INTERVAL_MS = 2000

/** 后端作业是否已结束（DONE 为终态）。 */
export function isBatchDone(state) {
  return state === DONE_STATE
}

/** 是否应停止轮询（达到最大次数后放弃，避免无限轮询）。 */
export function shouldStopPolling(attempt, maxAttempts = MAX_POLL_ATTEMPTS) {
  return attempt >= maxAttempts
}

/**
 * 构造批量答谢完成提示。
 * @param done 成功人数（可能为 number/string/null）
 * @param failed 失败人数（可能为 number/string/null）
 * @returns {{failed: number, message: string, hasFailure: boolean}}
 */
export function buildDoneMessage(done, failed) {
  const n = Number(failed || 0)
  const ok = Number(done || 0)
  const message = `批量答谢完成：成功 ${ok} 人` + (n > 0 ? `，失败 ${n} 人` : '')
  return { failed: n, message, hasFailure: n > 0 }
}
