<template>
  <section class="laboratory-detail">
    <PageHeader :title="laboratory?.name || '实验室详情'">
      <template #extra>
        <RouterLink class="back-link" to="/laboratories">返回实验室列表</RouterLink>
      </template>
    </PageHeader>

    <div v-loading="state === 'loading'" class="detail-content">
      <EmptyState v-if="state === 'error'" :title="`加载失败：${errorMessage}`">
        <template #action>
          <el-button type="primary" @click="loadDetail">重试</el-button>
        </template>
      </EmptyState>

      <EmptyState v-else-if="state === 'ready' && !laboratory" title="实验室不存在">
        <template #action>
          <el-button type="primary" @click="router.replace('/laboratories')">
            返回实验室列表
          </el-button>
        </template>
      </EmptyState>

      <template v-else-if="state === 'ready' && laboratory">
        <el-card shadow="never" class="info-card">
          <el-descriptions :column="3" border>
            <el-descriptions-item label="校区">{{ laboratory.campus }}</el-descriptions-item>
            <el-descriptions-item label="楼栋">{{ laboratory.building }}</el-descriptions-item>
            <el-descriptions-item label="房间">{{ laboratory.room }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <StatusTag enum-name="LaboratoryStatus" :value="laboratory.status" />
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">
              {{ formatDateTime(laboratory.createTime) }}
            </el-descriptions-item>
            <el-descriptions-item label="更新时间">
              {{ formatDateTime(laboratory.updateTime) }}
            </el-descriptions-item>
          </el-descriptions>
          <div class="description-block">
            <h2>实验室简介</h2>
            <p>{{ laboratory.description || '—' }}</p>
          </div>
        </el-card>

        <section class="equipment-section" aria-labelledby="laboratory-equipment-title">
          <div class="section-heading">
            <div>
              <h2 id="laboratory-equipment-title">该实验室的设备</h2>
              <span>共 {{ equipmentTotal }} 台设备</span>
            </div>
            <RouterLink
              v-if="equipmentTotal > PAGE_SIZE"
              :to="{ path: '/equipment', query: { laboratoryId: String(laboratory.id) } }"
            >
              在设备列表中查看全部
            </RouterLink>
          </div>

          <EmptyState v-if="equipment.length === 0" title="该实验室暂无设备" />
          <el-table v-else :data="equipment" border>
            <el-table-column prop="name" label="设备名称" min-width="180" />
            <el-table-column prop="categoryName" label="分类" min-width="130" />
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
        </section>
      </template>
    </div>
  </section>
</template>

<script setup>
import { ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { fetchEquipmentPage } from '../../api/equipment.js'
import { fetchLaboratoryById } from '../../api/laboratory.js'
import EmptyState from '../../components/EmptyState.vue'
import PageHeader from '../../components/PageHeader.vue'
import StatusTag from '../../components/StatusTag.vue'
import { PAGE_SIZE } from '../../constants/pagination.js'
import { formatDateTime } from '../../utils/datetime.js'
import { isNumericId } from '../../utils/query.js'

const route = useRoute()
const router = useRouter()
const state = ref('loading')
const laboratory = ref(null)
const equipment = ref([])
const equipmentTotal = ref(0)
const errorMessage = ref('')
let requestSequence = 0

async function loadDetail() {
  const sequence = ++requestSequence
  const id = route.params.id
  state.value = 'loading'
  laboratory.value = null
  equipment.value = []
  equipmentTotal.value = 0
  errorMessage.value = ''

  if (!isNumericId(id)) {
    state.value = 'ready'
    return
  }

  const laboratoryResult = await fetchLaboratoryById(id)
  if (sequence !== requestSequence) {
    return
  }
  if (laboratoryResult.kind === 'unauthorized') {
    return
  }
  if (['business', 'error', 'clientError'].includes(laboratoryResult.kind)) {
    errorMessage.value = laboratoryResult.message
    state.value = 'error'
    return
  }
  if (laboratoryResult.kind === 'network') {
    errorMessage.value = laboratoryResult.message
    state.value = 'error'
    return
  }
  if (laboratoryResult.kind !== 'ok') {
    errorMessage.value = laboratoryResult.message
    state.value = 'error'
    return
  }
  if (laboratoryResult.data === null) {
    state.value = 'ready'
    return
  }

  laboratory.value = laboratoryResult.data
  const equipmentResult = await fetchEquipmentPage({
    current: 1,
    laboratoryId: String(id),
  })
  if (sequence !== requestSequence) {
    return
  }
  if (equipmentResult.kind === 'unauthorized') {
    return
  }
  if (['business', 'error', 'clientError'].includes(equipmentResult.kind)) {
    errorMessage.value = equipmentResult.message
    state.value = 'error'
    return
  }
  if (equipmentResult.kind === 'network') {
    errorMessage.value = equipmentResult.message
    state.value = 'error'
    return
  }
  if (equipmentResult.kind !== 'ok') {
    errorMessage.value = equipmentResult.message
    state.value = 'error'
    return
  }

  equipment.value = Array.isArray(equipmentResult.data) ? equipmentResult.data : []
  equipmentTotal.value = Number(equipmentResult.total) || 0
  state.value = 'ready'
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

.description-block {
  padding-top: 20px;
}

.description-block h2,
.section-heading h2 {
  margin: 0;
  color: #303133;
  font-size: 17px;
}

.description-block p {
  margin: 10px 0 0;
  color: #606266;
  line-height: 1.75;
  white-space: pre-wrap;
}

.equipment-section {
  margin-top: 24px;
  padding: 22px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #fff;
}

.section-heading,
.section-heading > div {
  display: flex;
  align-items: center;
}

.section-heading {
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 18px;
}

.section-heading > div {
  gap: 12px;
}

.section-heading span {
  color: #606266;
  font-size: 14px;
}

@media (max-width: 760px) {
  :deep(.el-descriptions__body) {
    overflow-x: auto;
  }

  .section-heading {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
