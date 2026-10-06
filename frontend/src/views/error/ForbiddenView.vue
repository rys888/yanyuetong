<template>
  <main class="error-page">
    <section class="error-card" aria-labelledby="error-title">
      <div class="error-code">403</div>
      <h1 id="error-title">无权访问</h1>
      <p>当前账号没有访问此页面的权限。</p>
      <el-button type="primary" :icon="Back" @click="goHome">返回首页</el-button>
    </section>
  </main>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { Back } from '@element-plus/icons-vue'

import { defaultRouteFor } from '../../router/guard.js'
import { useUserStore } from '../../stores/user.js'

const router = useRouter()
const userStore = useUserStore()

function goHome() {
  const target = userStore.isLogin
    ? defaultRouteFor(userStore.profile?.role)
    : '/login'
  router.push(target)
}
</script>

<style scoped>
.error-page {
  display: grid;
  min-height: 100vh;
  place-items: center;
  padding: 24px;
  background: #f5f7fa;
}

.error-card {
  width: min(100%, 480px);
  padding: 40px 34px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #fff;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
  text-align: center;
}

.error-code {
  color: #2c5f8d;
  font-size: 42px;
  font-weight: 700;
  line-height: 1;
}

h1 {
  margin: 14px 0 0;
  color: #303133;
  font-size: 22px;
}

p {
  margin: 12px 0 24px;
  color: #606266;
  line-height: 1.7;
}
</style>
