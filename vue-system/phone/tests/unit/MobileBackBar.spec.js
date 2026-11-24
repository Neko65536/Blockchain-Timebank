import { describe, it, expect, vi } from 'vitest'
import { shallowMount } from '@vue/test-utils'
import MobileBackBar from '@/components/MobileBackBar.vue'

function mountBar({ prev = null, currentPath = '/detail', fallbackPath = '/home', title = '详情' } = {}) {
  const back = vi.fn()
  const push = vi.fn()
  const wrapper = shallowMount(MobileBackBar, {
    propsData: { fallbackPath, title },
    mocks: {
      $router: { back, push, _previousRoute: prev },
      $route: { fullPath: currentPath, path: currentPath },
    },
    stubs: { 'el-button': { template: '<button class="back-btn" @click="$emit(\'click\')"><slot/></button>' } },
  })
  return { wrapper, back, push }
}

describe('MobileBackBar.onBack', () => {
  it('有有效前页时调用 back，不 push', () => {
    const { wrapper, back, push } = mountBar({
      prev: { name: 'HomePhone', fullPath: '/home', path: '/home' },
      currentPath: '/detail',
    })
    wrapper.vm.onBack()
    expect(back).toHaveBeenCalledTimes(1)
    expect(push).not.toHaveBeenCalled()
  })

  it('前页是登录页（LoginPhone）时回退到 fallbackPath', () => {
    const { wrapper, back, push } = mountBar({
      prev: { name: 'LoginPhone', fullPath: '/login', path: '/login' },
      currentPath: '/detail',
      fallbackPath: '/home',
    })
    wrapper.vm.onBack()
    expect(push).toHaveBeenCalledWith('/home')
    expect(back).not.toHaveBeenCalled()
  })

  it('无前页时回退到 fallbackPath', () => {
    const { wrapper, push } = mountBar({ prev: null, currentPath: '/detail', fallbackPath: '/home' })
    wrapper.vm.onBack()
    expect(push).toHaveBeenCalledWith('/home')
  })

  it('前页 path 为根路径 "/" 时回退到 fallbackPath', () => {
    const { wrapper, push } = mountBar({
      prev: { name: 'HomePhone', fullPath: '/', path: '/' },
      currentPath: '/detail',
      fallbackPath: '/home',
    })
    wrapper.vm.onBack()
    expect(push).toHaveBeenCalledWith('/home')
  })

  it('fallbackPath 与当前路径相同且前页无效时，兜底调用 back', () => {
    const { wrapper, back, push } = mountBar({
      prev: null,
      currentPath: '/home',
      fallbackPath: '/home',
    })
    wrapper.vm.onBack()
    expect(back).toHaveBeenCalledTimes(1)
    expect(push).not.toHaveBeenCalled()
  })

  it('点击返回按钮触发 onBack', () => {
    const { wrapper, back } = mountBar({
      prev: { name: 'HomePhone', fullPath: '/home', path: '/home' },
      currentPath: '/detail',
    })
    wrapper.find('.back-btn').trigger('click')
    expect(back).toHaveBeenCalledTimes(1)
  })

  it('渲染传入的 title', () => {
    const { wrapper } = mountBar({ title: '活动详情', currentPath: '/detail' })
    expect(wrapper.find('.mobile-back-bar__title').text()).toBe('活动详情')
  })
})
