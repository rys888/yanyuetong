<template>
  <section class="admin-reservation-page">
    <PageHeader title="预约记录" description="查看并核验负责实验室的预约记录。" />

    <div class="filter-panel" aria-label="预约记录筛选">
      <el-input
        :model-value="params.batchId"
        clearable
        placeholder="批次 ID"
        @update:model-value="updateFilter('batchId', $event)"
      />
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
      <el-button @click="resetFilters">重置</el-button>
    </div>

    <div v-loading="state === 'loading'" class="table-panel">
      <EmptyState v-if="state === 'error'" :title="`加载失败：${errorMessage}`">
        <template #action>
          <el-button type="primary" @click="loadReservations">重试</el-button>
        </template>
      </EmptyState>

      <EmptyState
        v-else-if="state === 'ready' && records.length === 0"
        :title="activeFilter ? '没有符合条件的预约' : '暂无预约记录'"
      >
        <template v-if="activeFilter" #action>
          <el-button @click="resetFilters">重置筛选</el-button>
        </template>
      </EmptyState>

      <template v-else-if="state === 'ready'">
        <el-table :data="records" border>
          <el-table-column prop="id" label="预约号" min-width="190" />
          <el-table-column prop="equipmentName" label="设备" min-width="160" />
          <el-table-column prop="laboratoryName" label="所属实验室" min-width="170" />
          <el-table-column label="使用时段" min-width="330">
            <template #default="{ row }">
              {{ formatDateTime(row.startTime) }} ~ {{ formatDateTime(row.endTime) }}
            </template>
          </el-table-column>
          <el-table-column prop="userId" label="预约人 ID" min-width="150" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <StatusTag enum-name="ReservationStatus" :value="row.status" />
            </template>
          </el-table-column>
          <el-table-column label="操作" min-width="100" align="center" fixed="right">
            <template #default="{ row }">
              <el-button
                v-for="action in reservationActions(row)"
                :key="action"
                link
                :loading="verifyingId === row.id"
                @click="handleReservationAction(row, action)"
              >
                {{ ADMIN_RESERVATION_ACTION_LABELS[action] }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-pagination
          class="pagination"
          background
          layout="prev, pager, next, total"
          :current-page="params.current"
          :page-size="PAGE_SIZE"
          :total="total"
          @current-change="changePage"
        />
      </template>
    </div>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import {
  fetchManageReservationPage,
  verifyReservation,
} from '../../../api/reservation.js'
import EmptyState from '../../../components/EmptyState.vue'
import PageHeader from '../../../components/PageHeader.vue'
import StatusTag from '../../../components/StatusTag.vue'
import { PAGE_SIZE, lastPageOf } from '../../../constants/pagination.js'
import { resolveStatus } from '../../../constants/statusMap.js'
import { formatDateTime } from '../../../utils/datetime.js'
import {
  buildManageReservationQuery,
  hasActiveManageReservationFilter,
  RESERVATION_STATUSES,
} from '../../../utils/query.js'

const ADMIN_RESERVATION_ACTION_LABELS = {
  verify: '核验',
}

const route = useRoute()
const router = useRouter()
const state = ref('loading')
const records = ref([])
const total = ref(0)
const errorMessage = ref('')
const verifyingId = ref('')
const params = computed(() => buildManageReservationQuery(route.query))
const activeFilter = computed(() => hasActiveManageReservationFilter(params.value))
const statusOptions = RESERVATION_STATUSES.map((value) => ({
  value,
  label: resolveStatus('ReservationStatus', value).label,
}))
let requestSequence = 0

function showError(result) {
  ElMessage.error(result.message)
}

function reservationActions(row) {
  return resolveStatus('ReservationStatus', row.status)?.manageActions ?? []
}

async function loadReservations() {
  const sequence = ++requestSequence
  state.value = 'loading'
  errorMessage.value = ''
  const result = await fetchManageReservationPage(params.value)
  if (sequence !== requestSequence) return
  if (result.kind === 'unauthorized') return
  if (result.kind === 'ok') {
    records.value = Array.isArray(result.data) ? result.data : []
    total.value = Number(result.total) || 0
    if (total.value > 0 && records.value.length === 0 && params.value.current > lastPageOf(total.value)) {
      await router.replace({ query: { ...route.query, current: String(lastPageOf(total.value)) } })
      return
    }
    state.value = 'ready'
    return
  }
  records.value = []
  total.value = 0
  errorMessage.value = result.message
  state.value = 'error'
}

async function handleVerify(row) {
  try {
    await ElMessageBox.confirm(`确定核验设备“${row.equipmentName}”的这条预约吗？`, '核验预约', {
      type: 'warning',
      confirmButtonText: '核验',
      cancelButtonText: '返回',
    })
  } catch {
    return
  }
  const statusBefore = row.status
  verifyingId.value = row.id
  const result = await verifyReservation(row.id)
  verifyingId.value = ''
  if (result.kind === 'unauthorized') return
  if (result.kind !== 'ok') {
    showError(result)
    return
  }
  await loadReservations()
  if (state.value !== 'ready') return
  const statusAfter = records.value.find(
    (item) => String(item.id) === String(row.id),
  )?.status
  if (statusAfter !== statusBefore) ElMessage.success('核验成功')
}

const ADMIN_RESERVATION_ACTION_HANDLERS = {
  verify: handleVerify,
}

function handleReservationAction(row, action) {
  return ADMIN_RESERVATION_ACTION_HANDLERS[action]?.(row)
}

function replaceFilters(changes) {
  const query = { ...params.value, ...changes, current: '1' }
  for (const key of Object.keys(query)) {
    if (query[key] === '' || query[key] === undefined || query[key] === null) delete query[key]
  }
  router.replace({ query })
}

function updateFilter(key, value) {
  replaceFilters({ [key]: value })
}

function resetFilters() {
  router.replace({ query: {} })
}

function changePage(current) {
  router.replace({ query: { ...route.query, current: String(current) } })
}

watch(() => route.query, loadReservations, { immediate: true })
</script>

<style scoped>
.filter-panel {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) minmax(180px, 1fr) auto;
  gap: 12px;
  margin-bottom: 18px;
  padding: 18px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #fff;
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

  .pagination {
    justify-content: center;
  }
}
</style>
