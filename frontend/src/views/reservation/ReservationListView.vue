<template>
  <section class="reservation-page">
    <PageHeader title="我的预约" />

    <div v-loading="state === 'loading'" class="table-panel">
      <EmptyState v-if="state === 'error'" :title="`加载失败：${errorMessage}`">
        <template #action>
          <el-button type="primary" @click="load">重试</el-button>
        </template>
      </EmptyState>

      <EmptyState
        v-else-if="state === 'ready' && records.length === 0"
        title="你还没有预约记录"
      >
        <template #action>
          <el-button type="primary" @click="router.push('/batches')">
            去预约
          </el-button>
        </template>
      </EmptyState>

      <template v-else-if="state === 'ready'">
        <el-table :data="records" border>
          <el-table-column prop="equipmentName" label="设备" min-width="180" />
          <el-table-column prop="laboratoryName" label="所属实验室" min-width="180" />
          <el-table-column label="使用时段" min-width="330">
            <template #default="{ row }">
              {{ formatDateTime(row.startTime) }} ~ {{ formatDateTime(row.endTime) }}
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <StatusTag enum-name="ReservationStatus" :value="row.status" />
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
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { fetchReservationPage } from '../../api/reservation.js'
import EmptyState from '../../components/EmptyState.vue'
import PageHeader from '../../components/PageHeader.vue'
import StatusTag from '../../components/StatusTag.vue'
import { PAGE_SIZE, lastPageOf } from '../../constants/pagination.js'
import { formatDateTime } from '../../utils/datetime.js'
import { buildReservationQuery } from '../../utils/query.js'

const route = useRoute()
const router = useRouter()
const state = ref('loading')
const records = ref([])
const total = ref(0)
const errorMessage = ref('')
const params = computed(() => buildReservationQuery(route.query))
let requestSequence = 0

async function load() {
  const sequence = ++requestSequence
  state.value = 'loading'
  errorMessage.value = ''

  const result = await fetchReservationPage({ current: params.value.current })
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

  records.value = []
  total.value = 0
  errorMessage.value = result.message
  state.value = 'error'
}

function changePage(page) {
  router.replace({ query: { ...route.query, current: String(page) } })
}

watch(() => route.query, load, { immediate: true })
</script>

<style scoped>
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
  .pagination {
    justify-content: center;
  }
}
</style>
