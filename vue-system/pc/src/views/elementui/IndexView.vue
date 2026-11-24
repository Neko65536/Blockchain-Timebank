<template>
  <el-container class="indexContainer">
    <el-header class="first-header">
      <div class="header-left">
        <span class="header-logo"></span>
        <span class="header-title">时光颐养 · 时间银行</span>
      </div>
      <div class="header-right">
        <el-dropdown>
          <span class="el-dropdown-link">
            <div style="margin-right: 10px;">
                <img v-if="squareUrl" class="avatar1" :src="squareUrl">
                <span v-else class="username1">
                    <div style="font-size: 30px;">
                        {{ userInfo.username?.charAt(0) }}
                    </div>
                </span>
            </div>
            <div style="color: white">
              {{ roleLabel }}：{{ userInfo.username }}
              <span v-if="userInfo.id != null && userInfo.id !== ''" style="opacity: 0.92; margin-left: 6px;">
                （ID {{ userInfo.id }}）
              </span>
            </div>
            <i class="el-icon-arrow-down el-icon--right"></i>
          </span>
          <el-dropdown-menu slot="dropdown">
            <el-dropdown-item>
              <i class="el-icon-user"></i>个人中心
            </el-dropdown-item>
            <el-dropdown-item>
              <button @click="logout" 
              style="background: none;
                    border: none;
                    padding: 0;
                    cursor: pointer;">
                    <i class="el-icon-switch-button"></i>退出登录</button>
            </el-dropdown-item>
          </el-dropdown-menu>
        </el-dropdown>
      </div>
    </el-header>
    <el-container class="boxContainer">
      <el-aside class="aside" width="auto">
        <el-menu class="el-menu-vertical-demo" @open="handleOpen" @close="handleClose" :collapse="isCollapse">
          <router-link to="/homeView" class="RouterLink">
            <el-menu-item index="1">
              <i class="el-icon-s-home"></i>
              <span v-if="!isCollapse" slot="title">主页</span> 
            </el-menu-item>
          </router-link>
          <router-link to="/usersView" class="RouterLink">
            <el-menu-item index="2">
              <i class="el-icon-user"></i>
              <span v-if="!isCollapse" slot="title">用户管理</span> 
            </el-menu-item>
          </router-link>
          <router-link to="/adminView" class="RouterLink">
            <el-menu-item index="3">
              <i class="el-icon-menu"></i>
              <span v-if="!isCollapse" slot="title">活动管理</span> 
            </el-menu-item>
          </router-link>
          <router-link to="/knowledgeView" class="RouterLink"><el-menu-item index="knowledge"><i class="el-icon-reading"></i><span v-if="!isCollapse" slot="title">知识库管理</span></el-menu-item></router-link>
          <router-link to="/agentView" class="RouterLink"><el-menu-item index="agent"><i class="el-icon-magic-stick"></i><span v-if="!isCollapse" slot="title">Agent工作台</span></el-menu-item></router-link>
          <router-link to="/noticeManageView" class="RouterLink">
            <el-menu-item index="3a">
              <i class="el-icon-bell"></i>
              <span v-if="!isCollapse" slot="title">公告管理</span>
            </el-menu-item>
          </router-link>
          <router-link to="/coinView" class="RouterLink">
            <el-menu-item index="4">
              <i class="el-icon-coin"></i>
              <span v-if="!isCollapse" slot="title">时间币管理</span> 
            </el-menu-item>
          </router-link>
          <router-link to="/compensationView" class="RouterLink">
            <el-menu-item index="4a">
              <i class="el-icon-refresh-left"></i>
              <span v-if="!isCollapse" slot="title">待追讨记录</span>
            </el-menu-item>
          </router-link>
          <router-link to="/systemView" class="RouterLink">
            <el-menu-item index="5">
              <i class="el-icon-s-platform"></i>
              <span v-if="!isCollapse" slot="title">系统管理</span> 
            </el-menu-item>
          </router-link>
        </el-menu>
      </el-aside>
      <el-container class="right">
        <el-header class="second-header">
          <el-radio-group v-model="isCollapse" style="margin-right: 20px;">
            <el-radio-button :label="true">
              <i class="el-icon-arrow-left"></i>
            </el-radio-button>
            <el-radio-button :label="false">
              <i class="el-icon-arrow-right"></i>
            </el-radio-button>
          </el-radio-group>
          <el-tabs v-model="activeTab" @tab-remove="removeTab"  @tab-click="handleTabClick" type="card" closable
            style="padding-top: 12px">
            <el-tab-pane
              v-for="tab in openedTabs"
              :key="tab.path"
              :label="tab.title"
              :name="tab.path"
              closable>
            </el-tab-pane>
          </el-tabs>
        </el-header>
        <el-main class="mainBox">
          <router-view></router-view>
        </el-main>
      </el-container>
    </el-container>
  </el-container>
</template>

<script>
import request from '@/utils/request';
import { removeToken } from '@/utils/auth';

export default {
    name: 'IndexView',
    data() {
      return {
          // 折叠
          isCollapse: true,
          // 打开的标签页列表
          openedTabs: [
            { title: '主页', path: '/homeView', closable: false }, // Home标签默认打开且不可关闭
          ],
          activeTab: '/homeView',
          userInfo: {},
          squareUrl: '',
      }
    },
    created() {
      // 在页面加载时调用获取个人信息的方法
      this.fetchUserInfo();
      this.initializeTabFromCurrentRoute();
    },
    watch: {
      '$route'(to) {
        let tabExists = this.openedTabs.some(tab => tab.path === to.path);
        if (!tabExists) {
          this.openedTabs.push({
            title: this.getTitle(to.path), // 假设路由名称用作标签标题
            path: to.path,
            closable: true,
          });
        }
        this.activeTab = to.path;
      },
    },
    methods: {
      getTitle(path) {
        // 根据路由路径返回对应的中文标题
        switch (path) {
          case '/homeView':
            return '首页';
          case '/usersView':
            return '用户管理';
          case '/adminView':
            return '活动管理';
          case '/addActivityView':
            return '添加活动';
          case '/addUserView':
            return '添加用户';
          case '/systemView':
            return '系统管理';
          case '/coinView':
            return '时间币管理';
          case '/compensationView':
            return '待追讨记录';
          case '/knowledgeView':
            return '知识库管理';
          case '/agentView':
              return 'Agent工作台';
          case '/noticeManageView':
            return '公告管理';
          default:
            return path; // 默认返回路由路径
        }
      },
      // 获取个人信息
      fetchUserInfo() {
        // 使用 Axios 实例发送请求获取个人信息
        request.get('/info')
          .then(response => {
            // 假设返回的数据中包含用户信息
            this.userInfo = response.data;
          })
          .catch(error => {
            console.error('获取个人信息失败:', error);
          });
      },
      // 折叠菜单
      handleOpen(key, keyPath) {
        console.log(key, keyPath);
      },
      handleClose(key, keyPath) {
        console.log(key, keyPath);
      },
      // 退出登录
      async logout() {
        // 调用action
        await this.$store.dispatch('logout');
        // 执行退出登录的操作，清除本地存储中的 token
        removeToken('token');
        // 重定向到登录页面
        this.$router.push('/');
      },
      removeTab(targetPath) {
        const targetTab = this.openedTabs.find(tab => tab.path === targetPath);
        // 如果标签不可关闭，则直接返回
        if (targetTab && !targetTab.closable) {
          return;
        }

        let nextTab = null;
        const currentIndex = this.openedTabs.findIndex(tab => tab.path === targetPath);
        if (currentIndex !== -1) {
          // 查找下一个要激活的标签
          nextTab = this.openedTabs[currentIndex + 1] || this.openedTabs[currentIndex - 1];
          // 从数组中移除当前标签
          this.openedTabs.splice(currentIndex, 1);
        }

        // 如果当前标签是激活状态，或者移除后没有标签了，则尝试激活其他标签
        if (this.activeTab === targetPath || this.openedTabs.length === 0) {
          if (nextTab) {
            this.activeTab = nextTab.path;
            this.$router.push(nextTab.path).catch(err => {
              if (err.name !== 'NavigationDuplicated') {
                throw err;
              }
            });
          } else {
            // 如果没有其他标签可以激活，可以选择重定向到一个默认页面，比如首页
            this.$router.push('/defaultPage').catch(err => {
              if (err.name !== 'NavigationDuplicated') {
                throw err;
              }
            });
          }
        }
      },
      handleTabClick(tab) {
        if (this.$route.path !== tab.name) {
          this.$router.push(tab.name).catch(err => {
            if (err.name !== 'NavigationDuplicated') {
              console.error(err);
            }
          });
        }
      },
      // 刷新之后变化
      initializeTabFromCurrentRoute() {
        const currentPath = this.$route.path;

        // 如果当前路由已经是打开的标签之一，则不执行任何操作
        const tabExists = this.openedTabs.some(tab => tab.path === currentPath);
        if (!tabExists) {
          // 除非是默认的 homeView 路由，否则添加到 openedTabs
          if (currentPath !== '/homeView') {
            this.openedTabs.push({
              title: this.getTitle(currentPath), // 使用路由名称或备用标题
              path: currentPath,
              closable: true, // 除 homeView 外的标签应该是可关闭的
            });
          }
          this.activeTab = currentPath; // 更新当前激活的标签
        }
      }
  },
  computed: {
      roleLabel() {
        const role = Number(this.userInfo && this.userInfo.role);
        if (role === 1) return '老人';
        if (role === 2) return '志愿者';
        if (role === 3) return '管理员';
        return '用户';
      }
  },
};
</script>

<style lang="scss" scoped>
// 折叠效果
.el-menu-vertical-demo:not(.el-menu--collapse) {
  width: 200px;
}
.header-logo {
  background-image: url('@/assets/myResource/system.jpg');
  background-size: cover;
  background-repeat: no-repeat;
  background-position: center;
  display: inline-block;
  width: 40px;
  height: 40px;
  border-radius: 8px;
}
.header-title {
  font-size: 20px;
  font-weight: 600;
  letter-spacing: 1px;
  margin-left: 12px;
}
.indexContainer {
  height: 100vh;
  background: #f0f2f5;
  display: flex;
  flex-direction: column;

  .first-header {
    background: linear-gradient(90deg, #1e3c72 0%, #2a5298 100%);
    color: #fff;
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 0 20px;
    height: 60px;
    flex-shrink: 0;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);

    .header-left {
      display: flex;
      align-items: center;
    }
    .header-right {
      display: flex;
      align-items: center;
      .avatar1 {
        display: inline-block;
        width: 36px;
        height: 36px;
        border-radius: 50%;
        background-color: #d9d9d9;
        object-fit: cover;
      }
      .username1 {
        display: inline-block;
        width: 36px;
        height: 36px;
        text-align: center;
        line-height: 36px;
        border-radius: 50%;
        background: #10b981;
        color: #fff;
        margin-right: 4px;
      }
      .el-dropdown-link {
        display: flex;
        align-items: center;
        cursor: pointer;
        color: #fff;
      }
    }
  }
  .boxContainer {
    flex: 1;
    display: flex;
    overflow: hidden;

    .aside {
      margin: 12px 0 12px 12px;
      background: #fff;
      border-radius: 12px;
      box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
      overflow: hidden;
      flex-shrink: 0;

      .el-menu-vertical-demo {
        border-right: none;
        border-radius: 12px;
        background: #fff;

        .RouterLink {
          color: #333;
          text-decoration: none;
        }
      }
    }

    .right {
      flex: 1;
      display: flex;
      flex-direction: column;
      overflow: hidden;

      .second-header {
        display: flex;
        align-items: center;
        margin: 12px 12px 0;
        padding: 0 12px;
        background: #fff;
        border-radius: 12px;
        box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
        flex-shrink: 0;
      }
      .mainBox {
        flex: 1;
        margin: 12px;
        padding: 20px;
        background: #fff;
        border-radius: 12px;
        box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
        overflow: auto;
      }
    }
  }
}
</style>
