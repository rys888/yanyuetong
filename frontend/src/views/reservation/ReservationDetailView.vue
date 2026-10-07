<template>
  <section class="reservation-detail">
    <PageHeader title="预约详情">
      <template #extra>
        <RouterLink class="back-link" to="/reservations">返回我的预约</RouterLink>
      </template>
    </PageHeader>

    <div v-loading="state === 'loading'" class="detail-content">
      <EmptyState v-if="state === 'denied'" :title="errorMessage">
        <template #action>
          <el-button type="primary" @click="router.replace('/reservations')">
            返回我的预约
          </el-button>
        </template>
      </EmptyState>

      <EmptyState v-else-if="state === 'error'" :title="`加载失败：${errorMessage}`">
        <template #action>
          <el-button type="primary" @click="loadDetail">重试</el-button>
        </template>
      </EmptyState>

      <EmptyState
        v-else-if="state === 'ready' && !reservation"
        title="无法显示该预约"
        description="预约编号无效或未返回预约信息。"
      >
        <template #action>
          <el-button type="primary" @click="router.replace('/reservations')">
            返回我的预约
          </el-button>
        </template>
      </EmptyState>

      <el-card
        v-else-if="state === 'ready' && reservation"
        shadow="never"
        class="info-card"
      >
        <el-descriptions :column="2" border>
          <el-descriptions-item label="预约编号">
            {{ reservation.id }}
          </el-descriptions-item>
          <el-descriptions-item label="设备">
            {{ reservation.equipmentName }}
          </el-descriptions-item>
          <el-descriptions-item label="所属实验室">
            {{ reservation.laboratoryName }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <StatusTag enum-name="ReservationStatus" :value="reservation.status" />
          </el-descriptions-item>
          <el-descriptions-item label="使用时段">
            {{ formatDateTime(reservation.startTime) }} ~
            {{ formatDateTime(reservation.endTime) }}
          </el-descriptions-item>
          <el-descriptions-item label="预约开放窗口">
            {{ formatDateTime(reservation.bookingStartTime) }} ~
            {{ formatDateTime(reservation.bookingEndTime) }}
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">
            {{ formatDateTime(reservation.createTime) }}
          </el-descriptions-item>
          <el-descriptions-item label="更新时间">
            {{ formatDateTime(reservation.updateTime) }}
          </el-descriptions-item>
        </el-descriptions>

        <div v-if="canCancel" class="action-area">
          <el-button type="danger" :loading="cancelling" @click="handleCancel">
            取消预约
          </el-button>
        </div>
      </el-card>
    </div>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { cancelReservation, fetchReservationById } from '../../api/reservation.js'
import EmptyState from '../../components/EmptyState.vue'
import PageHeader from '../../components/PageHeader.vue'
import StatusTag from '../../components/StatusTag.vue'
import { resolveStatus } from '../../constants/statusMap.js'
import { formatDateTime } from '../../utils/datetime.js'
import { isNumericId } from '../../utils/query.js'

const route = useRoute()
const router = useRouter()
const state = ref('loading')
const reservation = ref(null)
const errorMessage = ref('')
const cancelling = ref(false)
const reservationStatusMeta = computed(() =>
  resolveStatus('ReservationStatus', reservation.value?.status),
)
const canCancel = computed(() => reservationStatusMeta.value.actions.includes('cancel'))
let requestSequence = 0

async function loadDetail() {
  const sequence = ++requestSequence
  const id = route.params.id
  state.value = 'loading'
  reservation.value = null
  errorMessage.value = ''

  if (!isNumericId(id)) {
    state.value = 'ready'
    return
  }

  const result = await fetchReservationById(id)
  if (sequence !== requestSequence) {
    return
  }
  if (result.kind === 'unauthorized') {
    errorMessage.value = result.message
    state.value = 'error'
    return
  }
  if (result.kind === 'denied') {
    errorMessage.value = result.message
    state.value = 'denied'
    return
  }
  if (result.kind === 'business' || (result.kind === 'ok' && !result.data)) {
    state.value = 'ready'
    return
  }
  if (result.kind === 'ok') {
    reservation.value = result.data
    state.value = 'ready'
    return
  }

  errorMessage.value = result.message
  state.value = 'error'
}

async function handleCancel() {
  if (cancelling.value || !canCancel.value) return

  try {
    await ElMessageBox.confirm('确定取消该预约吗？', '取消预约', {
      type: 'warning',
      confirmButtonText: '确认取消',
      cancelButtonText: '暂不取消',
    })
  } catch {
    return
  }

  const id = route.params.id
  cancelling.value = true
  const result = await cancelReservation(id)
  cancelling.value = false

  if (result.kind === 'unauthorized') {
    return
  }

  // 后端 TRANSITIONED 与 ALREADY_CANCELLED 都返回同形的 Result.ok()；重复取消是
  // 静默幂等路径，后端刻意不碰 Redis 以防名额泄漏。200 不能证明本次发生了状态
  // 迁移，任何成功类提示在幂等路径上都会误导，故必须重新拉取服务端最新状态。
  if (result.status >= 200 && result.status < 300) {
    await loadDetail()
    return
  }

  ElMessage.error(result.message)
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

.action-area {
  margin-top: 22px;
}

@media (max-width: 768px) {
  :deep(.el-descriptions__body) {
    overflow-x: auto;
  }
}
</style>
