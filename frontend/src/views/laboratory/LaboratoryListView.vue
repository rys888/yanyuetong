<template>
  <section class="laboratory-page">
    <PageHeader
      title="实验室"
      description="浏览校内实验室的资源与开放状态。"
    />

    <div v-loading="state === 'loading'" class="content-panel">
      <EmptyState v-if="state === 'error'" :title="`加载失败：${errorMessage}`">
        <template #action>
          <el-button type="primary" @click="loadLaboratories">重试</el-button>
        </template>
      </EmptyState>

      <EmptyState
        v-else-if="state === 'ready' && records.length === 0"
        title="暂无实验室数据"
      />

      <template v-else-if="state === 'ready'">
        <div class="laboratory-grid">
          <RouterLink
            v-for="laboratory in records"
            :key="laboratory.id"
            class="laboratory-card"
            :to="`/laboratories/${laboratory.id}`"
          >
            <div class="card-heading">
              <h2>{{ laboratory.name }}</h2>
              <StatusTag enum-name="LaboratoryStatus" :value="laboratory.status" />
            </div>
            <p class="location">
              {{ laboratory.campus }} · {{ laboratory.building }} {{ laboratory.room }}
            </p>
            <p class="description">{{ laboratory.description || '—' }}</p>
            <span class="detail-link">查看详情</span>
          </RouterLink>
        </div>

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
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { fetchLaboratoryList } from '../../api/laboratory.js'
import EmptyState from '../../components/EmptyState.vue'
import PageHeader from '../../components/PageHeader.vue'
import StatusTag from '../../components/StatusTag.vue'
import { PAGE_SIZE, lastPageOf } from '../../constants/pagination.js'
import { buildLaboratoryQuery } from '../../utils/query.js'

const route = useRoute()
const router = useRouter()
const state = ref('loading')
const records = ref([])
const total = ref(0)
const errorMessage = ref('')
const params = computed(() => buildLaboratoryQuery(route.query))
let requestSequence = 0

async function loadLaboratories() {
  const sequence = ++requestSequence
  state.value = 'loading'
  errorMessage.value = ''

  const result = await fetchLaboratoryList(params.value)
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

  if (['business', 'error', 'clientError'].includes(result.kind)) {
    records.value = []
    total.value = 0
    errorMessage.value = result.message
    state.value = 'error'
    return
  }
  if (result.kind === 'network') {
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

function changePage(current) {
  router.replace({ query: { ...route.query, current: String(current) } })
}

watch(() => route.query, loadLaboratories, { immediate: true })
</script>

<style scoped>
.content-panel {
  min-height: 260px;
}

.laboratory-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}

.laboratory-card {
  display: flex;
  min-height: 178px;
  flex-direction: column;
  padding: 22px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  background: #fff;
  color: inherit;
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
}

.laboratory-card:hover,
.laboratory-card:focus-visible {
  border-color: #2c5f8d;
  box-shadow: 0 5px 16px rgba(44, 95, 141, 0.1);
}

.laboratory-card:focus-visible {
  outline: 2px solid #2c5f8d;
  outline-offset: 2px;
}

.card-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}

h2 {
  margin: 0;
  color: #303133;
  font-size: 17px;
  line-height: 1.45;
}

.location {
  margin: 10px 0 0;
  color: #606266;
  font-size: 14px;
}

.description {
  display: -webkit-box;
  margin: 13px 0 18px;
  overflow: hidden;
  color: #606266;
  line-height: 1.65;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.detail-link {
  margin-top: auto;
  color: #2c5f8d;
  font-size: 14px;
  font-weight: 600;
}

.pagination {
  justify-content: flex-end;
  margin-top: 22px;
}

@media (max-width: 760px) {
  .laboratory-grid {
    grid-template-columns: 1fr;
  }

  .pagination {
    justify-content: center;
  }
}
</style>
