<template>
  <section class="dlq-list-page">
    <PageHeader title="死信治理" description="查看预约死信记录及处理状态。" />

    <div class="filter-panel" aria-label="死信记录筛选">
      <el-select
        :model-value="params.status"
        clearable
        placeholder="全部状态"
        @update:model-value="updateStatus"
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
          <el-button type="primary" @click="loadRecords">重试</el-button>
        </template>
      </EmptyState>

      <EmptyState
        v-else-if="state === 'ready' && records.length === 0"
        :title="activeFilter ? '没有符合条件的死信记录' : '暂无死信记录'"
      >
        <template v-if="activeFilter" #action>
          <el-button @click="resetFilters">重置筛选</el-button>
        </template>
      </EmptyState>

      <template v-else-if="state === 'ready'">
        <el-table :data="records" border>
          <el-table-column prop="id" label="死信 ID" min-width="190" />
          <el-table-column prop="reservationId" label="预约 ID" min-width="190" />
          <el-table-column prop="failureReason" label="失败原因" min-width="260" show-overflow-tooltip />
          <el-table-column prop="replayCount" label="重放次数" width="100" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <StatusTag enum-name="ReservationDlqStatus" :value="row.status" />
            </template>
          </el-table-column>
          <el-table-column label="创建时间" min-width="170">
            <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="110" align="center" fixed="right">
            <template #default="{ row }">
              <el-button link @click="viewDetail(row.id)">查看详情</el-button>
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
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { fetchReservationDlqPage } from '../../../api/dlq.js'
import EmptyState from '../../../components/EmptyState.vue'
import PageHeader from '../../../components/PageHeader.vue'
import StatusTag from '../../../components/StatusTag.vue'
import { ENUM_VALUES } from '../../../constants/enums.js'
import { PAGE_SIZE, lastPageOf } from '../../../constants/pagination.js'
import { resolveStatus } from '../../../constants/statusMap.js'
import { formatDateTime } from '../../../utils/datetime.js'
import { buildDlqQuery, hasActiveDlqFilter } from '../../../utils/query.js'

const route = useRoute()
const router = useRouter()
const state = ref('loading')
const records = ref([])
const total = ref(0)
const errorMessage = ref('')
const params = computed(() => buildDlqQuery(route.query))
const activeFilter = computed(() => hasActiveDlqFilter(params.value))
const statusOptions = ENUM_VALUES.ReservationDlqStatus.map((value) => ({
  value,
  label: resolveStatus('ReservationDlqStatus', value).label,
}))
let requestSequence = 0

function showError(result) {
  ElMessage.error(result.message)
}

function selectListFields(row) {
  return {
    id: row.id,
    reservationId: row.reservationId,
    failureReason: row.failureReason,
    replayCount: row.replayCount,
    status: row.status,
    createTime: row.createTime,
    updateTime: row.updateTime,
  }
}

async function loadRecords() {
  const sequence = ++requestSequence
  state.value = 'loading'
  errorMessage.value = ''
  const result = await fetchReservationDlqPage(params.value)
  if (sequence !== requestSequence) return
  if (result.kind === 'unauthorized') {
    errorMessage.value = result.message
    state.value = 'error'
    return
  }
  if (result.kind === 'ok') {
    records.value = Array.isArray(result.data) ? result.data.map(selectListFields) : []
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

function replaceFilters(status) {
  const query = { ...params.value, status, current: '1' }
  for (const key of Object.keys(query)) {
    if (query[key] === '' || query[key] === undefined || query[key] === null) delete query[key]
  }
  router.replace({ query })
}

function updateStatus(value) {
  replaceFilters(value)
}

function resetFilters() {
  router.replace({ query: {} })
}

function changePage(current) {
  router.replace({ query: { ...route.query, current: String(current) } })
}

function viewDetail(id) {
  router.push({ name: 'admin-dlq-detail', params: { id } })
}

watch(() => route.query, loadRecords, { immediate: true })
</script>

<style scoped>
.filter-panel {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) auto;
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

@media (max-width: 768px) {
  .filter-panel {
    grid-template-columns: 1fr;
  }

  .pagination {
    justify-content: center;
  }
}
</style>
