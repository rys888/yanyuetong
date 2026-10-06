<template>
  <el-container class="admin-layout">
    <el-aside class="admin-aside" :width="isCollapsed ? '64px' : '220px'">
      <RouterLink class="admin-brand" to="/admin" aria-label="研约通管理后台">
        <el-icon :size="22"><School /></el-icon>
        <span v-show="!isCollapsed">研约通管理端</span>
      </RouterLink>

      <el-menu
        class="admin-menu"
        router
        :collapse="isCollapsed"
        :collapse-transition="false"
        :default-active="route.path"
      >
        <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
          <el-icon v-if="resolveMenuIcon(item.icon)">
            <component :is="resolveMenuIcon(item.icon)" />
          </el-icon>
          <template #title>{{ item.title }}</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container class="admin-content">
      <el-header class="admin-header" height="56px">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item>管理后台</el-breadcrumb-item>
          <el-breadcrumb-item>{{ breadcrumbTitle }}</el-breadcrumb-item>
        </el-breadcrumb>

        <el-dropdown trigger="click" @command="handleCommand">
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
              <el-dropdown-item command="logout">
                <el-icon><SwitchButton /></el-icon>
                退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>

      <el-main class="admin-main">
        <RouterView />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import * as ElementPlusIcons from '@element-plus/icons-vue'
import { ArrowDown, School, SwitchButton } from '@element-plus/icons-vue'

import { resolveStatus } from '../constants/statusMap.js'
import { useUserStore } from '../stores/user.js'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const isCollapsed = ref(false)
const loggingOut = ref(false)

// 前端权限只是 UI 控制，真正的安全边界是后端的 RoleInterceptor + 服务层归属校验；前端隐藏入口不等于禁止访问。
const menuItems = computed(() =>
  router
    .getRoutes()
    .filter((record) => record.meta.menu && record.path.startsWith('/admin'))
    .filter(
      (record) =>
        !record.meta.roles || record.meta.roles.includes(userStore.profile?.role),
    )
    .sort((left, right) => left.meta.menu.order - right.meta.menu.order)
    .map((record) => ({ path: record.path, ...record.meta.menu })),
)

const breadcrumbTitle = computed(
  () => route.meta.menu?.title || route.meta.title || '管理后台',
)
const avatarText = computed(() => userStore.profile?.nickName?.trim().charAt(0) || '研')
const roleLabel = computed(
  () => resolveStatus('UserRole', userStore.profile?.role).label,
)

function resolveMenuIcon(iconName) {
  return ElementPlusIcons[iconName] ?? null
}

function updateCollapseState() {
  isCollapsed.value = window.innerWidth <= 992
}

async function handleCommand(command) {
  if (command !== 'logout' || loggingOut.value) {
    return
  }

  loggingOut.value = true
  await userStore.logout()
  await router.replace('/login')
}

onMounted(() => {
  updateCollapseState()
  window.addEventListener('resize', updateCollapseState)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', updateCollapseState)
})
</script>

<style scoped>
.admin-layout {
  min-height: 100vh;
  background: #f5f7fa;
}

.admin-aside {
  overflow: hidden;
  border-right: 1px solid #dcdfe6;
  background: #fff;
}

.admin-brand {
  display: flex;
  align-items: center;
  height: 56px;
  gap: 10px;
  padding: 0 20px;
  border-bottom: 1px solid #e4e7ed;
  color: #234c71;
  font-size: 17px;
  font-weight: 700;
  white-space: nowrap;
}

.admin-menu {
  border-right: 0;
}

.admin-menu:not(.el-menu--collapse) {
  width: 219px;
}

.admin-content {
  min-width: 0;
}

.admin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  border-bottom: 1px solid #e4e7ed;
  background: #fff;
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
  line-height: 1.25;
}

.user-copy strong {
  max-width: 140px;
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

.admin-main {
  padding: 24px;
}

@media (max-width: 640px) {
  .admin-header {
    padding: 0 14px;
  }

  .user-copy {
    display: none;
  }

  .admin-main {
    padding: 16px 12px;
  }
}
</style>
