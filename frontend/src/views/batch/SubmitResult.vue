<template>
  <section v-if="phase !== 'idle'" class="submit-result" aria-live="polite">
    <template v-if="phase === 'submitting'">
      <h2>正在提交预约请求</h2>
      <p>请稍候，不要重复提交。</p>
    </template>

    <template v-else-if="phase === 'accepted' || phase === 'polling'">
      <h2>预约请求已提交</h2>
      <p class="reservation-id">预约编号：{{ reservationId }}</p>
      <p>正在确认预约结果，请稍候。</p>
    </template>

    <template v-else-if="phase === 'done' && reservation">
      <div class="result-heading">
        <h2>预约成功</h2>
        <StatusTag enum-name="ReservationStatus" :value="reservation.status" />
      </div>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="预约编号">
          {{ reservationId }}
        </el-descriptions-item>
        <el-descriptions-item label="设备">
          {{ reservation.equipmentName }}
        </el-descriptions-item>
        <el-descriptions-item label="所属实验室">
          {{ reservation.laboratoryName }}
        </el-descriptions-item>
        <el-descriptions-item label="使用时段">
          {{ formatDateTime(reservation.startTime) }} ~
          {{ formatDateTime(reservation.endTime) }}
        </el-descriptions-item>
      </el-descriptions>
    </template>

    <template v-else-if="phase === 'pending'">
      <h2>预约结果待确认</h2>
      <p>暂时无法确认最终结果，请稍后在“我的预约”中查看。</p>
      <el-button type="primary" @click="emit('view-mine')">查看我的预约</el-button>
    </template>

    <template v-else-if="phase === 'failed' && failure">
      <h2>{{ failure.title }}</h2>
      <p>{{ failure.message }}</p>
      <el-button
        v-if="failure.action === 'retry'"
        type="primary"
        @click="emit('retry')"
      >
        重试
      </el-button>
      <el-button
        v-else-if="failure.action === 'mine'"
        type="primary"
        @click="emit('view-mine')"
      >
        查看我的预约
      </el-button>
    </template>
  </section>
</template>

<script setup>
import StatusTag from '../../components/StatusTag.vue'
import { formatDateTime } from '../../utils/datetime.js'

defineProps({
  phase: {
    type: String,
    required: true,
    validator: (value) =>
      ['idle', 'submitting', 'accepted', 'polling', 'done', 'pending', 'failed'].includes(
        value,
      ),
  },
  reservationId: {
    type: String,
    default: '',
  },
  reservation: {
    type: Object,
    default: null,
  },
  failure: {
    type: Object,
    default: null,
  },
})

const emit = defineEmits(['retry', 'view-mine'])
</script>

<style scoped>
.submit-result {
  margin-top: 18px;
  padding: 18px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #f5f7fa;
}

.submit-result h2 {
  margin: 0;
  color: #303133;
  font-size: 18px;
}

.submit-result p {
  margin: 10px 0 0;
  color: #606266;
  line-height: 1.6;
}

.submit-result .el-button {
  margin-top: 14px;
}

.reservation-id {
  overflow-wrap: anywhere;
  color: #2c5f8d;
  font-weight: 600;
}

.result-heading {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

@media (max-width: 560px) {
  .submit-result {
    padding: 14px;
  }
}
</style>
