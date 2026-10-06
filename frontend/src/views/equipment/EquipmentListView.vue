<template>
  <section class="equipment-page">
    <PageHeader
      title="设备"
      description="按实验室、分类、名称与状态筛选设备。"
    />

    <div class="filter-panel" aria-label="设备筛选">
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
        :model-value="params.categoryId"
        clearable
        placeholder="全部分类"
        @update:model-value="updateFilter('categoryId', $event)"
      >
        <el-option
          v-for="item in categoryOptions"
          :key="item.id"
          :label="item.name"
          :value="String(item.id)"
        />
      </el-select>
      <el-input
        v-model="nameInput"
        clearable
        placeholder="按设备名称搜索"
        @keyup.enter="submitName"
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
      <div class="filter-actions">
        <el-button type="primary" @click="submitName">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </div>
    </div>

    <div v-loading="state === 'loading'" class="table-panel">
      <EmptyState v-if="state === 'error'" :title="`加载失败：${errorMessage}`">
        <template #action>
          <el-button type="primary" @click="loadEquipment">重试</el-button>
        </template>
      </EmptyState>

      <EmptyState
        v-else-if="state === 'ready' && records.length === 0"
        :title="activeFilter ? '没有符合条件的设备' : '暂无设备数据'"
      >
        <template v-if="activeFilter" #action>
          <el-button type="primary" @click="resetFilters">重置筛选</el-button>
        </template>
      </EmptyState>

      <template v-else-if="state === 'ready'">
        <el-table :data="records" border>
          <el-table-column prop="name" label="设备名称" min-width="180" />
          <el-table-column prop="laboratoryName" label="所属实验室" min-width="190" />
          <el-table-column prop="categoryName" label="分类" min-width="120" />
          <el-table-column prop="location" label="位置" min-width="150" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <StatusTag enum-name="EquipmentStatus" :value="row.status" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" align="center">
            <template #default="{ row }">
              <RouterLink :to="`/equipment/${row.id}`">详情</RouterLink>
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
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { fetchEquipmentCategories, fetchEquipmentPage } from '../../api/equipment.js'
import { fetchLaboratoryList } from '../../api/laboratory.js'
import EmptyState from '../../components/EmptyState.vue'
import PageHeader from '../../components/PageHeader.vue'
import StatusTag from '../../components/StatusTag.vue'
import { ENUM_VALUES } from '../../constants/enums.js'
import { PAGE_SIZE, lastPageOf } from '../../constants/pagination.js'
import { resolveStatus } from '../../constants/statusMap.js'
import { buildEquipmentQuery, hasActiveFilter } from '../../utils/query.js'

const route = useRoute()
const router = useRouter()
const state = ref('loading')
const records = ref([])
const total = ref(0)
const errorMessage = ref('')
const laboratoryOptions = ref([])
const categoryOptions = ref([])
const nameInput = ref('')
const params = computed(() => buildEquipmentQuery(route.query))
const activeFilter = computed(() => hasActiveFilter(params.value))
const statusOptions = ENUM_VALUES.EquipmentStatus.map((value) => ({
  value,
  label: resolveStatus('EquipmentStatus', value).label,
}))
let requestSequence = 0

async function loadEquipment() {
  const sequence = ++requestSequence
  state.value = 'loading'
  errorMessage.value = ''

  const result = await fetchEquipmentPage(params.value)
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

async function loadFilterOptions() {
  const [laboratoryResult, categoryResult] = await Promise.all([
    fetchLaboratoryList({ current: 1 }),
    fetchEquipmentCategories(),
  ])
  if (laboratoryResult.kind === 'unauthorized') {
    return
  }
  if (laboratoryResult.kind === 'ok') {
    laboratoryOptions.value = Array.isArray(laboratoryResult.data)
      ? laboratoryResult.data
      : []
  } else {
    laboratoryOptions.value = []
  }

  if (categoryResult.kind === 'unauthorized') {
    return
  }
  if (categoryResult.kind === 'ok') {
    categoryOptions.value = Array.isArray(categoryResult.data)
      ? categoryResult.data
      : []
  } else {
    categoryOptions.value = []
  }
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

function submitName() {
  nameInput.value = nameInput.value.trim()
  replaceFilters({ name: nameInput.value })
}

function resetFilters() {
  nameInput.value = ''
  router.replace({ query: {} })
}

function changePage(current) {
  router.replace({ query: { ...params.value, current: String(current) } })
}

watch(() => route.query, loadEquipment, { immediate: true })
watch(
  () => params.value.name,
  (name) => {
    nameInput.value = name ?? ''
  },
  { immediate: true },
)
onMounted(loadFilterOptions)
</script>

<style scoped>
.filter-panel {
  display: grid;
  grid-template-columns: repeat(4, minmax(150px, 1fr)) auto;
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

@media (max-width: 1080px) {
  .filter-panel {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 600px) {
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
