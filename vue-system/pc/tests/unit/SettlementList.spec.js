import { describe, it, expect, vi, afterEach } from 'vitest'
import { shallowMount } from '@vue/test-utils'
import SettlementList from '@/components/SettlementList.vue'
import request from '@/utils/request'

vi.mock('@/utils/request', () => ({ default: { get: vi.fn(), post: vi.fn() } }))

// @vue/test-utils@1 无 flushPromises，用宏任务让挂起中的 promise 链执行完。
const flushPromises = () => new Promise((resolve) => setTimeout(resolve, 0))

const stubs = {
  'el-card': { template: '<div><slot/><slot name="header"/></div>' },
  'el-table': { template: '<div><slot/></div>' },
  'el-table-column': true,
  'el-button': true,
}

describe('SettlementList', () => {
  let wrapper
  afterEach(() => { wrapper && wrapper.destroy() })

  it('typeLabel 将业务类型映射为中文', () => {
    wrapper = shallowMount(SettlementList, { propsData: { admin: false }, stubs })
    expect(wrapper.vm.typeLabel('PUBLISH_FEE')).toBe('发布扣费')
    expect(wrapper.vm.typeLabel('VOLUNTEER_REWARD')).toBe('服务答谢')
    expect(wrapper.vm.typeLabel('USER_TRANSFER')).toBe('转账')
    expect(wrapper.vm.typeLabel('ADMIN_TRANSFER')).toBe('管理员转账')
    expect(wrapper.vm.typeLabel('ADMIN_MINT')).toBe('增发')
    expect(wrapper.vm.typeLabel('UNKNOWN')).toBe('UNKNOWN')
  })

  it('admin 为 true 时请求管理员结算接口', async () => {
    request.get.mockResolvedValue({ code: 1, data: [] })
    wrapper = shallowMount(SettlementList, { propsData: { admin: true }, stubs })
    await flushPromises()
    expect(request.get).toHaveBeenCalledWith('/administrator/chain/settlements')
  })

  it('load 成功后填充 rows 并清空 error', async () => {
    request.get.mockResolvedValue({
      code: 1,
      data: [{ settlementId: 'S1', bizType: 'USER_TRANSFER', state: 'COMPLETED', amount: 10 }],
    })
    wrapper = shallowMount(SettlementList, { propsData: { admin: false }, stubs })
    await flushPromises()
    expect(wrapper.vm.rows).toHaveLength(1)
    expect(wrapper.vm.rows[0].settlementId).toBe('S1')
    expect(wrapper.vm.error).toBe('')
  })

  it('load 失败时给出提示', async () => {
    request.get.mockResolvedValue({ code: 0, msg: '失败' })
    wrapper = shallowMount(SettlementList, { propsData: { admin: false }, stubs })
    await flushPromises()
    expect(wrapper.vm.error).toBe('结算记录加载失败，请稍后刷新')
  })
})
