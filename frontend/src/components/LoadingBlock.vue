<template>
  <div v-loading="true" class="loading-block" :style="{ minHeight }" />
</template>

<script setup>
defineProps({
  minHeight: {
    type: String,
    default: '160px',
  },
})
</script>

<style scoped>
.loading-block {
  width: 100%;
}
</style>
