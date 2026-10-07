<template>
  <section class="dlq-detail-page">
    <PageHeader title="死信详情" description="查看死信记录与原始消息内容。">
      <template #extra>
        <RouterLink :to="{ name: 'admin-dlq-list' }">返回死信列表</RouterLink>
      </template>
    </PageHeader>

    <div v-loading="state === 'loading'" class="detail-panel">
      <EmptyState v-if="state === 'error'" :title="`加载失败：${errorMessage}`">
        <template #action>
          <el-button type="primary" @click="loadDetail">重试</el-button>
          <RouterLink :to="{ name: 'admin-dlq-list' }">
            <el-button>返回死信列表</el-button>
          </RouterLink>
        </template>
      </EmptyState>

      <EmptyState v-else-if="state === 'ready' && !detail" title="死信记录不存在">
        <template #action>
          <RouterLink :to="{ name: 'admin-dlq-list' }">
            <el-button type="primary">返回死信列表</el-button>
          </RouterLink>
        </template>
      </EmptyState>

      <template v-else-if="state === 'ready'">
        <div class="detail-actions">
          <el-button type="danger" :loading="replaying" @click="handleReplay">重放死信</el-button>
        </div>

        <el-alert
          v-if="replayFailureText"
          class="replay-alert"
          type="error"
          :title="replayFailureText"
          :closable="false"
          show-icon
        >
          <el-collapse v-if="replayFailureCode" v-model="expandedFailureSections">
            <el-collapse-item title="查看原始返回" name="original">
              <pre>{{ replayFailureOriginal }}</pre>
            </el-collapse-item>
          </el-collapse>
        </el-alert>

        <el-descriptions :column="2" border>
          <el-descriptions-item label="死信 ID">{{ detail.id }}</el-descriptions-item>
          <el-descriptions-item label="预约 ID">{{ detail.reservationId }}</el-descriptions-item>
          <el-descriptions-item label="失败原因" :span="2">{{ detail.failureReason }}</el-descriptions-item>
          <el-descriptions-item label="重放次数">{{ detail.replayCount }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <StatusTag enum-name="ReservationDlqStatus" :value="detail.status" />
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatDateTime(detail.createTime) }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ formatDateTime(detail.updateTime) }}</el-descriptions-item>
          <el-descriptions-item label="消息内容" :span="2">
            <div class="payload-box"><pre>{{ formattedPayload }}</pre></div>
          </el-descriptions-item>
        </el-descriptions>
      </template>
    </div>
  </section>
</template>

<script setup>
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import { fetchReservationDlqById, replayReservationDlq } from '../../../api/dlq.js'
import EmptyState from '../../../components/EmptyState.vue'
import PageHeader from '../../../components/PageHeader.vue'
import StatusTag from '../../../components/StatusTag.vue'
import { describeDlqReplayReason } from '../../../constants/messages.js'
import { resolveStatus } from '../../../constants/statusMap.js'
import { formatDateTime } from '../../../utils/datetime.js'
import { formatJsonText } from '../../../utils/jsonText.js'

const route = useRoute()
const state = ref('loading')
const detail = ref(null)
const errorMessage = ref('')
const replaying = ref(false)
const replayFailureText = ref('')
const replayFailureCode = ref(null)
const replayFailureOriginal = ref('')
const expandedFailureSections = ref([])
const formattedPayload = computed(() => formatJsonText(detail.value?.payload))
const dlqStatus = computed(() => resolveStatus('ReservationDlqStatus', detail.value?.status))
const canReplay = computed(() => dlqStatus.value?.actions?.includes('replay') ?? false)
let requestSequence = 0

async function loadDetail() {
  const sequence = ++requestSequence
  state.value = 'loading'
  errorMessage.value = ''
  const result = await fetchReservationDlqById(route.params.id)
  if (sequence !== requestSequence) return
  if (result.kind === 'unauthorized') {
    errorMessage.value = result.message
    state.value = 'error'
    return
  }
  if (result.kind === 'business' || (result.kind === 'ok' && result.data === null)) {
    detail.value = null
    state.value = 'ready'
    return
  }
  if (result.kind === 'ok') {
    detail.value = result.data
    state.value = 'ready'
    return
  }
  detail.value = {}
  errorMessage.value = result.message
  state.value = 'error'
}

async function handleReplay() {
  replayFailureText.value = ''
  replayFailureCode.value = null
  replayFailureOriginal.value = ''
  expandedFailureSections.value = []

  const confirmation = canReplay.value
    ? '确定重放这条死信吗？原消息会被重新投递到队列。'
    : `该记录当前状态为「${dlqStatus.value.label}」，后端会拒绝本次重放。仍要提交吗？`
  try {
    await ElMessageBox.confirm(confirmation, '重放死信', {
      type: 'warning',
      confirmButtonText: '重放',
      cancelButtonText: '返回',
    })
  } catch {
    return
  }
  const statusBefore = detail.value.status
  replaying.value = true
  const result = await replayReservationDlq(route.params.id)
  replaying.value = false
  if (result.kind === 'unauthorized') return
  if (result.kind !== 'ok') {
    const { text, code } = describeDlqReplayReason(result.message)
    replayFailureText.value = text
    replayFailureCode.value = code
    replayFailureOriginal.value = result.message ?? ''
    return
  }
  await loadDetail()
  if (state.value !== 'ready') return
  if (detail.value.status !== statusBefore) {
    ElMessage.success('重放已提交，状态已更新为「已重放」')
  }
}

watch(() => route.params.id, loadDetail, { immediate: true })
</script>

<style scoped>
.detail-panel {
  min-height: 300px;
  max-width: 100%;
  padding: 18px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #fff;
}

.payload-box {
  max-width: 100%;
}

.detail-actions {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 16px;
}

.replay-alert {
  margin-bottom: 16px;
}

.replay-alert :deep(.el-alert__content) {
  width: 100%;
}

.replay-alert :deep(.el-collapse) {
  margin-top: 10px;
  border-bottom: 0;
}

pre {
  max-height: 420px;
  margin: 0;
  overflow-y: auto;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  line-height: 1.6;
}
</style>
