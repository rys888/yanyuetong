<template>
  <section class="placeholder-card" aria-labelledby="home-title">
    <h1 id="home-title">首页</h1>
    <p class="phase-note">实验室与设备浏览将在 F3 实现。</p>

    <dl class="identity-list">
      <div>
        <dt>当前用户</dt>
        <dd>{{ userStore.profile?.nickName }}</dd>
      </div>
      <div>
        <dt>当前角色</dt>
        <dd>{{ roleLabel }}</dd>
      </div>
    </dl>
  </section>
</template>

<script setup>
import { computed } from 'vue'

import { resolveStatus } from '../../constants/statusMap.js'
import { useUserStore } from '../../stores/user.js'

const userStore = useUserStore()
const roleLabel = computed(
  () => resolveStatus('UserRole', userStore.profile?.role).label,
)
</script>

<style scoped>
.placeholder-card {
  padding: 26px 28px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #fff;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}

h1 {
  margin: 0;
  color: #303133;
  font-size: 22px;
  line-height: 1.4;
}

.phase-note {
  margin: 8px 0 24px;
  color: #606266;
  line-height: 1.7;
}

.identity-list {
  display: grid;
  margin: 0;
  grid-template-columns: repeat(2, minmax(0, 220px));
  gap: 16px;
}

.identity-list div {
  padding: 14px 16px;
  border-left: 3px solid #2c5f8d;
  background: #f5f7fa;
}

dt {
  color: #909399;
  font-size: 13px;
}

dd {
  margin: 6px 0 0;
  color: #303133;
  font-weight: 600;
}

@media (max-width: 560px) {
  .placeholder-card {
    padding: 22px 20px;
  }

  .identity-list {
    grid-template-columns: 1fr;
  }
}
</style>
