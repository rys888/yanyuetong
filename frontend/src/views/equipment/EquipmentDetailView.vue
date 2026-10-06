<template>
  <section class="equipment-detail">
    <PageHeader :title="equipment?.name || '设备详情'">
      <template #extra>
        <RouterLink class="back-link" to="/equipment">返回设备列表</RouterLink>
      </template>
    </PageHeader>

    <div v-loading="state === 'loading'" class="detail-content">
      <EmptyState v-if="state === 'error'" :title="`加载失败：${errorMessage}`">
        <template #action>
          <el-button type="primary" @click="loadDetail">重试</el-button>
        </template>
      </EmptyState>

      <EmptyState v-else-if="state === 'ready' && !equipment" title="设备不存在">
        <template #action>
          <el-button type="primary" @click="router.replace('/equipment')">
            返回设备列表
          </el-button>
        </template>
      </EmptyState>

      <el-card v-else-if="state === 'ready' && equipment" shadow="never" class="info-card">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="设备名称">{{ equipment.name }}</el-descriptions-item>
          <el-descriptions-item label="所属实验室">
            <RouterLink :to="`/laboratories/${equipment.laboratoryId}`">
              {{ equipment.laboratoryName }}
            </RouterLink>
          </el-descriptions-item>
          <el-descriptions-item label="分类">{{ equipment.categoryName }}</el-descriptions-item>
          <el-descriptions-item label="位置">{{ equipment.location }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <StatusTag enum-name="EquipmentStatus" :value="equipment.status" />
          </el-descriptions-item>
          <el-descriptions-item label="是否可预约">
            {{ equipment.bookingEnabled === true ? '可预约' : '不可预约' }}
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">
            {{ formatDateTime(equipment.createTime) }}
          </el-descriptions-item>
          <el-descriptions-item label="更新时间">
            {{ formatDateTime(equipment.updateTime) }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="text-block">
          <h2>设备简介</h2>
          <p>{{ equipment.description || '—' }}</p>
        </div>
        <div class="text-block requirement-block">
          <h2>使用要求</h2>
          <p>{{ equipment.usageRequirement || '—' }}</p>
        </div>
      </el-card>
    </div>
  </section>
</template>

<script setup>
import { ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { fetchEquipmentById } from '../../api/equipment.js'
import EmptyState from '../../components/EmptyState.vue'
import PageHeader from '../../components/PageHeader.vue'
import StatusTag from '../../components/StatusTag.vue'
import { formatDateTime } from '../../utils/datetime.js'
import { isNumericId } from '../../utils/query.js'

const route = useRoute()
const router = useRouter()
const state = ref('loading')
const equipment = ref(null)
const errorMessage = ref('')
let requestSequence = 0

async function loadDetail() {
  const sequence = ++requestSequence
  const id = route.params.id
  state.value = 'loading'
  equipment.value = null
  errorMessage.value = ''

  if (!isNumericId(id)) {
    state.value = 'ready'
    return
  }

  const result = await fetchEquipmentById(id)
  if (sequence !== requestSequence) {
    return
  }
  if (result.kind === 'unauthorized') {
    return
  }
  if (result.kind === 'business' || (result.kind === 'ok' && result.data === null)) {
    state.value = 'ready'
    return
  }
  if (result.kind === 'ok') {
    equipment.value = result.data
    state.value = 'ready'
    return
  }
  if (['error', 'clientError'].includes(result.kind)) {
    errorMessage.value = result.message
    state.value = 'error'
    return
  }
  if (result.kind === 'network') {
    errorMessage.value = result.message
    state.value = 'error'
    return
  }

  errorMessage.value = result.message
  state.value = 'error'
}

watch(() => route.params.id, loadDetail, { immediate: true })
</script>

<style scoped>
.detail-content {
  min-height: 300px;
}

.back-link {
  color: #2c5f8d;
  font-weight: 600;
}

.info-card {
  border-radius: 4px;
}

.text-block {
  padding-top: 22px;
}

.text-block h2 {
  margin: 0;
  color: #303133;
  font-size: 17px;
}

.text-block p {
  margin: 10px 0 0;
  color: #606266;
  line-height: 1.75;
  white-space: pre-wrap;
}

.requirement-block {
  margin-top: 20px;
  border-top: 1px solid #e4e7ed;
}

@media (max-width: 760px) {
  :deep(.el-descriptions__body) {
    overflow-x: auto;
  }
}
</style>
