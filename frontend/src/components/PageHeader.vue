<template>
  <header class="page-header">
    <div class="page-copy">
      <h1>{{ title }}</h1>
      <p v-if="description">{{ description }}</p>
    </div>
    <div v-if="$slots.extra" class="page-extra">
      <slot name="extra" />
    </div>
  </header>
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
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 22px;
}

.page-copy {
  min-width: 0;
}

h1 {
  margin: 0;
  color: #303133;
  font-size: 22px;
  line-height: 1.4;
}

p {
  margin: 7px 0 0;
  color: #606266;
  line-height: 1.6;
}

.page-extra {
  flex: none;
  padding-top: 3px;
}

@media (max-width: 560px) {
  .page-header {
    flex-direction: column;
    gap: 10px;
  }

  .page-extra {
    padding-top: 0;
  }
}
</style>
