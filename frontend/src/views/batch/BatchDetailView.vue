<template>
  <section class="batch-detail">
    <PageHeader :title="batch?.equipmentName ? `预约批次详情` : '预约批次详情'">
      <template #extra>
        <RouterLink class="back-link" to="/batches">返回批次列表</RouterLink>
      </template>
    </PageHeader>

    <div v-loading="state === 'loading'" class="detail-content">
      <EmptyState v-if="state === 'error'" :title="`加载失败：${errorMessage}`">
        <template #action>
          <el-button type="primary" @click="loadDetail">重试</el-button>
        </template>
      </EmptyState>

      <EmptyState v-else-if="state === 'ready' && !batch" title="预约批次不存在">
        <template #action>
          <el-button type="primary" @click="router.replace('/batches')">
            返回批次列表
          </el-button>
        </template>
      </EmptyState>

      <el-card v-else-if="state === 'ready' && batch" shadow="never" class="info-card">
        <div class="status-row">
          <StatusTag enum-name="ReservationBatchStatus" :value="batch.status" />
          <StatusTag enum-name="BookingState" :value="batch.bookingState" />
        </div>

        <el-descriptions :column="2" border>
          <el-descriptions-item label="设备">
            <RouterLink :to="`/equipment/${batch.equipmentId}`">
              {{ batch.equipmentName }}
            </RouterLink>
          </el-descriptions-item>
          <el-descriptions-item label="所属实验室">
            <RouterLink :to="`/laboratories/${batch.laboratoryId}`">
              {{ batch.laboratoryName }}
            </RouterLink>
          </el-descriptions-item>
          <el-descriptions-item label="使用时段">
            {{ formatDateTime(batch.startTime) }} ~ {{ formatDateTime(batch.endTime) }}
          </el-descriptions-item>
          <el-descriptions-item label="预约开放窗口">
            {{ formatDateTime(batch.bookingStartTime) }} ~
            {{ formatDateTime(batch.bookingEndTime) }}
          </el-descriptions-item>
          <el-descriptions-item label="总名额">{{ batch.totalQuota }}</el-descriptions-item>
          <el-descriptions-item label="剩余名额">
            {{ batch.remainingQuota }}
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">
            {{ formatDateTime(batch.createTime) }}
          </el-descriptions-item>
          <el-descriptions-item label="更新时间">
            {{ formatDateTime(batch.updateTime) }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="submit-area">
          <el-button type="primary" :disabled="!canSubmit" @click="handleSubmit">
            立即预约
          </el-button>
          <span v-if="!canSubmit" class="submit-hint">
            当前不可预约：{{ bookingStateLabel }}
          </span>
        </div>
      </el-card>
    </div>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { fetchReservationBatchById } from '../../api/reservationBatch.js'
import EmptyState from '../../components/EmptyState.vue'
import PageHeader from '../../components/PageHeader.vue'
import StatusTag from '../../components/StatusTag.vue'
import { resolveStatus } from '../../constants/statusMap.js'
import { formatDateTime } from '../../utils/datetime.js'
import { isNumericId } from '../../utils/query.js'

const route = useRoute()
const router = useRouter()
const state = ref('loading')
const batch = ref(null)
const errorMessage = ref('')
const bookingState = computed(() => batch.value?.bookingState)
const bookingStateMeta = computed(() => resolveStatus('BookingState', bookingState.value))
const canSubmit = computed(() => bookingStateMeta.value.actions.includes('submit'))
const bookingStateLabel = computed(() => bookingStateMeta.value.label)
let requestSequence = 0

async function loadDetail() {
  const sequence = ++requestSequence
  const id = route.params.id
  state.value = 'loading'
  batch.value = null
  errorMessage.value = ''

  if (!isNumericId(id)) {
    state.value = 'ready'
    return
  }

  const result = await fetchReservationBatchById(id)
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
    batch.value = result.data
    state.value = 'ready'
    return
  }
  if (['error', 'clientError', 'network'].includes(result.kind)) {
    errorMessage.value = result.message
    state.value = 'error'
    return
  }

  errorMessage.value = result.message
  state.value = 'error'
}

function handleSubmit() {
  ElMessage.info('提交预约功能将在后续版本开放')
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

.status-row {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
}

.submit-area {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 22px;
}

.submit-hint {
  color: #606266;
  font-size: 14px;
}

@media (max-width: 760px) {
  :deep(.el-descriptions__body) {
    overflow-x: auto;
  }

  .submit-area {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
