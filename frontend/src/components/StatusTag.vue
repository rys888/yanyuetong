<template>
  <el-tag :type="resolved.tag" disable-transitions>{{ resolved.label }}</el-tag>
</template>

<script setup>
import { computed } from 'vue'

import { resolveStatus } from '../constants/statusMap.js'

const props = defineProps({
  enumName: {
    type: String,
    required: true,
  },
  value: {
    type: String,
    default: '',
  },
})

const resolved = computed(() => resolveStatus(props.enumName, props.value))
</script>
