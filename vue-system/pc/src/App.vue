<template>
  <body>
    <div id="app">
      <router-view></router-view>  <!-- 使用 <router-view> 渲染整个应用程序的内容 -->
    </div>
  </body>
</template>

<script>
export default {
  name: 'App'
}
</script>

<style>
/* 样式 */
body{
	margin:0;
	padding:0;
	border:0;
	background:#f0f2f5;
}

/* 统一主题色：深蓝系，与主布局顶栏一致 */
.el-button--primary {
  background-color: #2a5298;
  border-color: #2a5298;
}
.el-button--primary:hover,
.el-button--primary:focus {
  background-color: #3a6ab5;
  border-color: #3a6ab5;
}
.el-menu-item.is-active {
  background-color: #eef3fb !important;
  color: #2a5298 !important;
  border-right: 3px solid #2a5298;
}
.el-menu-item:hover {
  background-color: #f5f7fa !important;
}
.el-tabs__item.is-active {
  color: #2a5298;
}
.el-tabs__active-bar {
  background-color: #2a5298;
}
.el-link.el-link--primary {
  color: #2a5298;
}
</style>
