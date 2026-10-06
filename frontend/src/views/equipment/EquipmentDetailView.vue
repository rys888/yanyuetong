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

      <template v-else-if="state === 'ready' && equipment">
        <el-card shadow="never" class="info-card">
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

        <el-card shadow="never" class="batch-card">
          <div class="batch-heading">
            <h2>该设备的预约批次</h2>
            <span v-if="batchTotal > 0" class="batch-count">
              共 {{ batchTotal }} 个批次
            </span>
          </div>

          <div
            v-if="batchState === 'loading'"
            v-loading="batchState === 'loading'"
            class="batch-loading"
          />
          <p v-else-if="batchState === 'error'" class="batch-message">
            批次加载失败：{{ batchErrorMessage }}
          </p>
          <p
            v-else-if="batchState === 'ready' && batchRecords.length === 0"
            class="batch-message"
          >
            该设备暂无预约批次
          </p>
          <template v-else>
            <el-table :data="batchRecords" border>
              <el-table-column label="使用时段" min-width="320">
                <template #default="{ row }">
                  {{ formatDateTime(row.startTime) }} ~ {{ formatDateTime(row.endTime) }}
                </template>
              </el-table-column>
              <el-table-column label="开放窗口" min-width="320">
                <template #default="{ row }">
                  {{ formatDateTime(row.bookingStartTime) }} ~
                  {{ formatDateTime(row.bookingEndTime) }}
                </template>
              </el-table-column>
              <el-table-column label="剩余名额" width="110">
                <template #default="{ row }">
                  {{ row.remainingQuota }} / {{ row.totalQuota }}
                </template>
              </el-table-column>
              <el-table-column label="预约状态" width="120">
                <template #default="{ row }">
                  <StatusTag enum-name="BookingState" :value="row.bookingState" />
                </template>
              </el-table-column>
              <el-table-column label="操作" width="90" align="center">
                <template #default="{ row }">
                  <RouterLink :to="`/batches/${row.id}`">详情</RouterLink>
                </template>
              </el-table-column>
            </el-table>

            <RouterLink
              v-if="batchTotal > PAGE_SIZE"
              class="batch-more"
              :to="`/batches?equipmentId=${equipment.id}`"
            >
              在批次列表中查看全部
            </RouterLink>
          </template>
        </el-card>
      </template>
    </div>
  </section>
</template>

<script setup>
import { ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { fetchEquipmentById } from '../../api/equipment.js'
import { fetchReservationBatchPage } from '../../api/reservationBatch.js'
import EmptyState from '../../components/EmptyState.vue'
import PageHeader from '../../components/PageHeader.vue'
import StatusTag from '../../components/StatusTag.vue'
import { PAGE_SIZE } from '../../constants/pagination.js'
import { formatDateTime } from '../../utils/datetime.js'
import { isNumericId } from '../../utils/query.js'

const route = useRoute()
const router = useRouter()
const state = ref('loading')
const equipment = ref(null)
const errorMessage = ref('')
const batchState = ref('loading')
const batchRecords = ref([])
const batchTotal = ref(0)
const batchErrorMessage = ref('')
let requestSequence = 0
let batchRequestSequence = 0

async function loadDetail() {
  const sequence = ++requestSequence
  const id = route.params.id
  state.value = 'loading'
  equipment.value = null
  errorMessage.value = ''
  batchRequestSequence += 1
  batchState.value = 'loading'
  batchRecords.value = []
  batchTotal.value = 0
  batchErrorMessage.value = ''

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
    await loadBatches(id)
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

async function loadBatches(id) {
  const sequence = ++batchRequestSequence
  batchState.value = 'loading'
  batchRecords.value = []
  batchTotal.value = 0
  batchErrorMessage.value = ''

  const result = await fetchReservationBatchPage({ current: 1, equipmentId: String(id) })
  if (sequence !== batchRequestSequence) {
    return
  }
  if (result.kind === 'unauthorized') {
    return
  }
  if (result.kind === 'ok') {
    batchRecords.value = Array.isArray(result.data) ? result.data : []
    batchTotal.value = Number(result.total) || 0
    batchState.value = 'ready'
    return
  }

  batchErrorMessage.value = result.message
  batchState.value = 'error'
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

.batch-card {
  margin-top: 24px;
  border-radius: 4px;
}

.batch-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 18px;
}

.batch-heading h2 {
  margin: 0;
  color: #303133;
  font-size: 17px;
}

.batch-count,
.batch-message {
  color: #606266;
  font-size: 14px;
}

.batch-loading {
  min-height: 130px;
}

.batch-message {
  margin: 0;
  padding: 34px 12px;
  text-align: center;
}

.batch-more {
  display: block;
  margin-top: 18px;
  color: #2c5f8d;
  text-align: right;
  font-weight: 600;
}

@media (max-width: 760px) {
  :deep(.el-descriptions__body) {
    overflow-x: auto;
  }

  .batch-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }
}
</style>
