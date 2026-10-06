<template>
  <el-container class="app-layout">
    <el-header class="app-header" height="56px">
      <RouterLink class="brand" to="/home" aria-label="研约通首页">
        <el-icon :size="22"><School /></el-icon>
        <span>研约通</span>
      </RouterLink>

      <nav class="primary-nav" aria-label="学生端主导航">
        <el-menu
          class="header-menu"
          mode="horizontal"
          router
          :default-active="route.path"
          :ellipsis="false"
        >
          <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
            <el-icon v-if="resolveMenuIcon(item.icon)">
              <component :is="resolveMenuIcon(item.icon)" />
            </el-icon>
            <span>{{ item.title }}</span>
          </el-menu-item>
        </el-menu>
      </nav>

      <el-dropdown class="user-dropdown" trigger="click" @command="handleCommand">
        <button class="user-trigger" type="button" :disabled="loggingOut">
          <span class="text-avatar" aria-hidden="true">{{ avatarText }}</span>
          <span class="user-copy">
            <strong>{{ userStore.profile?.nickName }}</strong>
            <small>{{ roleLabel }}</small>
          </span>
          <el-icon><ArrowDown /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-if="userStore.isAdmin" command="admin">
              <el-icon><Setting /></el-icon>
              管理后台
            </el-dropdown-item>
            <el-dropdown-item command="logout" divided>
              <el-icon><SwitchButton /></el-icon>
              退出登录
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </el-header>

    <el-main class="app-main">
      <RouterView />
    </el-main>
  </el-container>
</template>

<script setup>
import { computed, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import * as ElementPlusIcons from '@element-plus/icons-vue'
import { ArrowDown, School, Setting, SwitchButton } from '@element-plus/icons-vue'

import { resolveStatus } from '../constants/statusMap.js'
import { useUserStore } from '../stores/user.js'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const loggingOut = ref(false)

// 前端权限只是 UI 控制，真正的安全边界是后端的 RoleInterceptor + 服务层归属校验；前端隐藏入口不等于禁止访问。
const menuItems = computed(() =>
  router
    .getRoutes()
    .filter((record) => record.meta.menu && !record.path.startsWith('/admin'))
    .filter(
      (record) =>
        !record.meta.roles || record.meta.roles.includes(userStore.profile?.role),
    )
    .sort((left, right) => left.meta.menu.order - right.meta.menu.order)
    .map((record) => ({ path: record.path, ...record.meta.menu })),
)

const avatarText = computed(() => userStore.profile?.nickName?.trim().charAt(0) || '研')
const roleLabel = computed(
  () => resolveStatus('UserRole', userStore.profile?.role).label,
)

function resolveMenuIcon(iconName) {
  return ElementPlusIcons[iconName] ?? null
}

async function handleCommand(command) {
  if (command === 'admin') {
    await router.push('/admin')
    return
  }

  if (command !== 'logout' || loggingOut.value) {
    return
  }

  loggingOut.value = true
  await userStore.logout()
  await router.replace('/login')
}
</script>

<style scoped>
.app-layout {
  min-height: 100vh;
  background: #f5f7fa;
}

.app-header {
  display: grid;
  grid-template-columns: minmax(150px, 1fr) auto minmax(220px, 1fr);
  align-items: center;
  gap: 24px;
  padding: 0 28px;
  border-bottom: 1px solid #e4e7ed;
  background: #fff;
}

.brand {
  display: inline-flex;
  align-items: center;
  justify-self: start;
  gap: 9px;
  color: #234c71;
  font-size: 19px;
  font-weight: 700;
  letter-spacing: 0.06em;
}

.primary-nav,
.header-menu {
  height: 56px;
}

.header-menu {
  border-bottom: 0;
}

.header-menu :deep(.el-menu-item) {
  min-width: 92px;
  justify-content: center;
  padding: 0 22px;
}

.user-dropdown {
  justify-self: end;
}

.user-trigger {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 5px 0;
  border: 0;
  background: transparent;
  color: #303133;
  cursor: pointer;
}

.user-trigger:focus-visible {
  outline: 2px solid #2c5f8d;
  outline-offset: 3px;
}

.text-avatar {
  display: inline-grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 50%;
  background: #2c5f8d;
  color: #fff;
  font-weight: 700;
}

.user-copy {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  min-width: 72px;
  line-height: 1.25;
}

.user-copy strong {
  max-width: 128px;
  overflow: hidden;
  font-size: 14px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-copy small {
  margin-top: 2px;
  color: #909399;
  font-size: 12px;
}

.app-main {
  width: min(100%, 1240px);
  margin: 0 auto;
  padding: 28px;
}

@media (max-width: 760px) {
  .app-header {
    grid-template-columns: auto 1fr auto;
    gap: 8px;
    padding: 0 14px;
  }

  .brand span,
  .user-copy {
    display: none;
  }

  .header-menu :deep(.el-menu-item) {
    min-width: 72px;
    padding: 0 12px;
  }

  .app-main {
    padding: 18px 14px;
  }
}
</style>
