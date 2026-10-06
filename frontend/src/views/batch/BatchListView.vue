<template>
  <section class="batch-page">
    <PageHeader
      title="预约批次"
      description="浏览已发布与已关闭的预约批次，可按实验室与状态筛选。"
    />

    <div class="filter-panel" aria-label="批次筛选">
      <el-select
        :model-value="params.laboratoryId"
        clearable
        placeholder="全部实验室"
        @update:model-value="updateFilter('laboratoryId', $event)"
      >
        <el-option
          v-for="item in laboratoryOptions"
          :key="item.id"
          :label="item.name"
          :value="String(item.id)"
        />
      </el-select>
      <el-select
        :model-value="params.status"
        clearable
        placeholder="全部状态"
        @update:model-value="updateFilter('status', $event)"
      >
        <el-option
          v-for="item in statusOptions"
          :key="item.value"
          :label="item.label"
          :value="item.value"
        />
      </el-select>
      <div class="filter-actions">
        <el-button @click="resetFilters">重置</el-button>
      </div>
    </div>

    <div v-if="params.equipmentId" class="equipment-filter">
      <span>仅看设备 #{{ params.equipmentId }} 的批次</span>
      <el-button text type="primary" @click="replaceFilters({ equipmentId: undefined })">
        清除
      </el-button>
    </div>

    <div v-loading="state === 'loading'" class="table-panel">
      <EmptyState v-if="state === 'error'" :title="`加载失败：${errorMessage}`">
        <template #action>
          <el-button type="primary" @click="loadBatches">重试</el-button>
        </template>
      </EmptyState>

      <EmptyState
        v-else-if="state === 'ready' && records.length === 0"
        :title="activeFilter ? '没有符合条件的批次' : '暂无可预约的批次'"
        :description="activeFilter ? '' : '当前没有已发布的预约批次，请稍后再来。'"
      >
        <template v-if="activeFilter" #action>
          <el-button type="primary" @click="resetFilters">重置筛选</el-button>
        </template>
      </EmptyState>

      <template v-else-if="state === 'ready'">
        <el-table :data="records" border>
          <el-table-column prop="equipmentName" label="设备" min-width="180">
            <template #default="{ row }">
              <RouterLink :to="`/equipment/${row.equipmentId}`">
                {{ row.equipmentName }}
              </RouterLink>
            </template>
          </el-table-column>
          <el-table-column
            prop="laboratoryName"
            label="所属实验室"
            min-width="180"
          />
          <el-table-column label="使用时段" min-width="330">
            <template #default="{ row }">
              {{ formatDateTime(row.startTime) }} ~ {{ formatDateTime(row.endTime) }}
            </template>
          </el-table-column>
          <el-table-column label="剩余名额" width="110">
            <template #default="{ row }">
              {{ row.remainingQuota }} / {{ row.totalQuota }}
            </template>
          </el-table-column>
          <el-table-column label="批次状态" width="110">
            <template #default="{ row }">
              <StatusTag enum-name="ReservationBatchStatus" :value="row.status" />
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

        <el-pagination
          class="pagination"
          background
          layout="prev, pager, next, total"
          :page-size="PAGE_SIZE"
          :current-page="params.current"
          :total="total"
          @current-change="changePage"
        />
      </template>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { fetchLaboratoryList } from '../../api/laboratory.js'
import { fetchReservationBatchPage } from '../../api/reservationBatch.js'
import EmptyState from '../../components/EmptyState.vue'
import PageHeader from '../../components/PageHeader.vue'
import StatusTag from '../../components/StatusTag.vue'
import { PAGE_SIZE, lastPageOf } from '../../constants/pagination.js'
import { resolveStatus } from '../../constants/statusMap.js'
import {
  BATCH_PUBLIC_STATUSES,
  buildBatchQuery,
  hasActiveBatchFilter,
} from '../../utils/query.js'
import { formatDateTime } from '../../utils/datetime.js'

const route = useRoute()
const router = useRouter()
const state = ref('loading')
const records = ref([])
const total = ref(0)
const errorMessage = ref('')
const laboratoryOptions = ref([])
const params = computed(() => buildBatchQuery(route.query))
const activeFilter = computed(() => hasActiveBatchFilter(params.value))
const statusOptions = BATCH_PUBLIC_STATUSES.map((value) => ({
  value,
  label: resolveStatus('ReservationBatchStatus', value).label,
}))
let requestSequence = 0

async function loadBatches() {
  const sequence = ++requestSequence
  state.value = 'loading'
  errorMessage.value = ''

  const result = await fetchReservationBatchPage(params.value)
  if (sequence !== requestSequence) {
    return
  }
  if (result.kind === 'unauthorized') {
    return
  }
  if (result.kind === 'ok') {
    records.value = Array.isArray(result.data) ? result.data : []
    total.value = Number(result.total) || 0

    if (
      total.value > 0 &&
      records.value.length === 0 &&
      params.value.current > lastPageOf(total.value)
    ) {
      await router.replace({
        query: { ...route.query, current: String(lastPageOf(total.value)) },
      })
      return
    }

    state.value = 'ready'
    return
  }

  if (['business', 'error', 'clientError', 'network'].includes(result.kind)) {
    records.value = []
    total.value = 0
    errorMessage.value = result.message
    state.value = 'error'
    return
  }

  records.value = []
  total.value = 0
  errorMessage.value = result.message
  state.value = 'error'
}

async function loadFilterOptions() {
  const result = await fetchLaboratoryList({ current: 1 })
  if (result.kind === 'unauthorized') {
    return
  }
  if (result.kind === 'ok') {
    laboratoryOptions.value = Array.isArray(result.data) ? result.data : []
    return
  }
  laboratoryOptions.value = []
}

function replaceFilters(changes) {
  const query = { ...params.value, ...changes, current: '1' }
  for (const key of Object.keys(query)) {
    if (query[key] === '' || query[key] === undefined || query[key] === null) {
      delete query[key]
    }
  }
  router.replace({ query })
}

function updateFilter(key, value) {
  replaceFilters({ [key]: value })
}

function resetFilters() {
  router.replace({ query: {} })
}

function changePage(page) {
  router.replace({ query: { ...route.query, current: String(page) } })
}

watch(() => route.query, loadBatches, { immediate: true })
onMounted(loadFilterOptions)
</script>

<style scoped>
.filter-panel {
  display: grid;
  grid-template-columns: repeat(2, minmax(180px, 1fr)) auto;
  gap: 12px;
  margin-bottom: 18px;
  padding: 18px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #fff;
}

.filter-actions {
  display: flex;
}

.equipment-filter {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin: -4px 0 18px;
  padding: 10px 14px;
  border-left: 3px solid #2c5f8d;
  background: #f5f7fa;
  color: #606266;
}

.table-panel {
  min-height: 300px;
  padding: 18px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #fff;
}

.pagination {
  justify-content: flex-end;
  margin-top: 20px;
}

@media (max-width: 760px) {
  .filter-panel {
    grid-template-columns: 1fr;
  }

  .filter-actions,
  .filter-actions :deep(.el-button) {
    flex: 1;
  }

  .pagination {
    justify-content: center;
  }
}
</style>
