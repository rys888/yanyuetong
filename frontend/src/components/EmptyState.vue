<template>
  <el-empty class="empty-state">
    <template #description>
      <p class="empty-title">{{ title }}</p>
      <p v-if="description" class="empty-description">{{ description }}</p>
    </template>
    <slot name="action" />
  </el-empty>
</template>

<script setup>
defineProps({
  title: {
    type: String,
    required: true,
  },
  description: {
    type: String,
    default: '',
  },
})
</script>

<style scoped>
.empty-state {
  padding: 44px 20px;
}

.empty-title {
  margin: 0;
  color: #303133;
  font-size: 16px;
  font-weight: 600;
}

.empty-description {
  margin: 8px 0 0;
  color: #606266;
  line-height: 1.6;
}
</style>
