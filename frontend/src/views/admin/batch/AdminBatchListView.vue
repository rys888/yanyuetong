<template>
  <section class="admin-batch-page">
    <PageHeader title="我的批次" description="管理负责实验室的设备预约批次。">
      <template #extra>
        <el-button type="primary" @click="openCreateDialog">新建批次</el-button>
      </template>
    </PageHeader>

    <div class="filter-panel" aria-label="批次筛选">
      <el-input
        :model-value="params.equipmentId"
        clearable
        placeholder="设备 ID"
        @update:model-value="updateFilter('equipmentId', $event)"
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
          <el-button type="primary" @click="loadBatches">重试</el-button>
        </template>
      </EmptyState>

      <EmptyState
        v-else-if="state === 'ready' && records.length === 0"
        :title="activeFilter ? '没有符合条件的批次' : '暂无批次数据'"
      >
        <template #action>
          <el-button v-if="activeFilter" @click="resetFilters">重置筛选</el-button>
          <el-button v-else type="primary" @click="openCreateDialog">新建批次</el-button>
        </template>
      </EmptyState>

      <template v-else-if="state === 'ready'">
        <el-table :data="records" border>
          <el-table-column prop="equipmentName" label="设备" min-width="160" />
          <el-table-column prop="laboratoryName" label="所属实验室" min-width="170" />
          <el-table-column label="使用时段" min-width="330">
            <template #default="{ row }">
              {{ formatDateTime(row.startTime) }} ~ {{ formatDateTime(row.endTime) }}
            </template>
          </el-table-column>
          <el-table-column label="预约开放窗口" min-width="330">
            <template #default="{ row }">
              {{ formatDateTime(row.bookingStartTime) }} ~ {{ formatDateTime(row.bookingEndTime) }}
            </template>
          </el-table-column>
          <el-table-column label="名额" width="110">
            <template #default="{ row }">
              {{ row.remainingQuota }} / {{ row.totalQuota }}
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <StatusTag enum-name="ReservationBatchStatus" :value="row.status" />
            </template>
          </el-table-column>
          <el-table-column label="操作" min-width="220" align="center" fixed="right">
            <template #default="{ row }">
              <el-button
                v-for="action in batchActions(row)"
                :key="action"
                link
                :type="action === 'cancel' ? 'danger' : 'primary'"
                :loading="actionBatchId === row.id"
                @click="handleBatchAction(row, action)"
              >
                {{ BATCH_STATUS_ACTION_LABELS[action] }}
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

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑批次' : '新建批次'" width="680px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="118px">
        <el-form-item label="所属实验室" prop="laboratoryId">
          <el-select
            v-model="form.laboratoryId"
            class="full-width"
            filterable
            @change="handleLaboratoryChange"
          >
            <el-option
              v-for="item in laboratoryOptions"
              :key="item.id"
              :label="item.name"
              :value="String(item.id)"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="设备" prop="equipmentId">
          <el-select v-model="form.equipmentId" class="full-width" filterable>
            <el-option
              v-for="item in equipmentOptions"
              :key="item.id"
              :label="item.name"
              :value="String(item.id)"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="使用开始" prop="startTime">
          <el-date-picker
            v-model="form.startTime"
            class="full-width"
            type="datetime"
            value-format="YYYY-MM-DD[T]HH:mm:ss"
          />
        </el-form-item>
        <el-form-item label="使用结束" prop="endTime">
          <el-date-picker
            v-model="form.endTime"
            class="full-width"
            type="datetime"
            value-format="YYYY-MM-DD[T]HH:mm:ss"
          />
        </el-form-item>
        <el-form-item label="预约开放" prop="bookingStartTime">
          <el-date-picker
            v-model="form.bookingStartTime"
            class="full-width"
            type="datetime"
            value-format="YYYY-MM-DD[T]HH:mm:ss"
          />
        </el-form-item>
        <el-form-item label="预约截止" prop="bookingEndTime">
          <el-date-picker
            v-model="form.bookingEndTime"
            class="full-width"
            type="datetime"
            value-format="YYYY-MM-DD[T]HH:mm:ss"
          />
        </el-form-item>
        <el-form-item label="总名额" prop="totalQuota">
          <el-input-number v-model="form.totalQuota" class="full-width" controls-position="right" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="submitting" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { fetchEquipmentPage } from '../../../api/equipment.js'
import { fetchLaboratoryList } from '../../../api/laboratory.js'
import {
  cancelReservationBatch,
  closeReservationBatch,
  createReservationBatch,
  fetchManageBatchPage,
  publishReservationBatch,
  updateReservationBatch,
} from '../../../api/reservationBatch.js'
import EmptyState from '../../../components/EmptyState.vue'
import PageHeader from '../../../components/PageHeader.vue'
import StatusTag from '../../../components/StatusTag.vue'
import { ENUM_VALUES } from '../../../constants/enums.js'
import { PAGE_SIZE, lastPageOf } from '../../../constants/pagination.js'
import { resolveStatus } from '../../../constants/statusMap.js'
import { formatDateTime } from '../../../utils/datetime.js'
import {
  buildManageBatchQuery,
  hasActiveManageBatchFilter,
} from '../../../utils/query.js'

const BATCH_STATUS_ACTION_LABELS = {
  edit: '编辑',
  publish: '发布',
  close: '关闭',
  cancel: '取消',
}

const route = useRoute()
const router = useRouter()
const state = ref('loading')
const records = ref([])
const total = ref(0)
const errorMessage = ref('')
const laboratoryOptions = ref([])
const baseEquipmentOptions = ref([])
const editingEquipmentOption = ref(null)
const dialogVisible = ref(false)
const editingId = ref('')
const submitting = ref(false)
const actionBatchId = ref('')
const formRef = ref()
const params = computed(() => buildManageBatchQuery(route.query))
const activeFilter = computed(() => hasActiveManageBatchFilter(params.value))
const statusOptions = ENUM_VALUES.ReservationBatchStatus.map((value) => ({
  value,
  label: resolveStatus('ReservationBatchStatus', value).label,
}))
const equipmentOptions = computed(() => {
  const options = [...baseEquipmentOptions.value]
  const current = editingEquipmentOption.value
  if (current && !options.some((item) => String(item.id) === String(current.id))) options.push(current)
  return options
})
const form = reactive(emptyForm())
const rules = {
  laboratoryId: [{ required: true, message: '请选择所属实验室', trigger: 'change' }],
  equipmentId: [{ required: true, message: '请选择设备', trigger: 'change' }],
  startTime: [{ required: true, message: '请选择使用开始时间', trigger: 'change' }],
  endTime: [{ required: true, message: '请选择使用结束时间', trigger: 'change' }],
  bookingStartTime: [{ required: true, message: '请选择预约开放时间', trigger: 'change' }],
  bookingEndTime: [{ required: true, message: '请选择预约截止时间', trigger: 'change' }],
  totalQuota: [{ required: true, message: '请输入总名额', trigger: 'change' }],
}
let requestSequence = 0
let equipmentRequestSequence = 0

function emptyForm() {
  return {
    laboratoryId: '',
    equipmentId: '',
    startTime: '',
    endTime: '',
    bookingStartTime: '',
    bookingEndTime: '',
    totalQuota: undefined,
  }
}

function showError(result) {
  ElMessage.error(result.message)
}

function batchActions(row) {
  return resolveStatus('ReservationBatchStatus', row.status).actions
}

async function loadBatches() {
  const sequence = ++requestSequence
  state.value = 'loading'
  errorMessage.value = ''
  const result = await fetchManageBatchPage(params.value)
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

async function loadLaboratoryOptions() {
  const result = await fetchLaboratoryList({ current: 1 })
  if (result.kind === 'unauthorized') return
  laboratoryOptions.value = result.kind === 'ok' && Array.isArray(result.data) ? result.data : []
}

async function loadEquipmentOptions(laboratoryId) {
  const sequence = ++equipmentRequestSequence
  baseEquipmentOptions.value = []
  if (!laboratoryId) return true
  const result = await fetchEquipmentPage({ current: 1, laboratoryId })
  if (sequence !== equipmentRequestSequence) return false
  if (result.kind === 'unauthorized') return false
  baseEquipmentOptions.value = result.kind === 'ok' && Array.isArray(result.data) ? result.data : []
  return true
}

function resetForm(values = {}) {
  Object.assign(form, emptyForm(), values)
  nextTick(() => formRef.value?.clearValidate())
}

function openCreateDialog() {
  equipmentRequestSequence += 1
  editingId.value = ''
  editingEquipmentOption.value = null
  baseEquipmentOptions.value = []
  resetForm()
  dialogVisible.value = true
}

async function openEditDialog(row) {
  editingId.value = row.id
  editingEquipmentOption.value = { id: row.equipmentId, name: row.equipmentName }
  resetForm({
    laboratoryId: String(row.laboratoryId ?? ''),
    equipmentId: String(row.equipmentId ?? ''),
    startTime: row.startTime ?? '',
    endTime: row.endTime ?? '',
    bookingStartTime: row.bookingStartTime ?? '',
    bookingEndTime: row.bookingEndTime ?? '',
    totalQuota: row.totalQuota,
  })
  const loaded = await loadEquipmentOptions(form.laboratoryId)
  if (!loaded) return
  dialogVisible.value = true
}

async function handleLaboratoryChange(laboratoryId) {
  form.equipmentId = ''
  editingEquipmentOption.value = null
  await loadEquipmentOptions(laboratoryId)
}

function formPayload() {
  return {
    equipmentId: form.equipmentId,
    startTime: form.startTime,
    endTime: form.endTime,
    bookingStartTime: form.bookingStartTime,
    bookingEndTime: form.bookingEndTime,
    totalQuota: form.totalQuota,
  }
}

async function handleSave() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  const wasEditing = Boolean(editingId.value)
  const payload = formPayload()
  const result = wasEditing
    ? await updateReservationBatch({ ...payload, id: editingId.value })
    : await createReservationBatch(payload)
  submitting.value = false
  if (result.kind === 'unauthorized') return
  if (result.kind !== 'ok') {
    showError(result)
    return
  }
  await loadBatches()
  dialogVisible.value = false
  ElMessage.success(wasEditing ? '已保存' : '已创建')
}

async function handlePublish(row) {
  actionBatchId.value = row.id
  const result = await publishReservationBatch(row.id)
  actionBatchId.value = ''
  if (result.kind === 'unauthorized') return
  if (result.kind !== 'ok') {
    showError(result)
    return
  }
  await loadBatches()
  ElMessage.success('已发布')
}

async function handleClose(row) {
  actionBatchId.value = row.id
  const result = await closeReservationBatch(row.id)
  actionBatchId.value = ''
  if (result.kind === 'unauthorized') return
  if (result.kind !== 'ok') {
    showError(result)
    return
  }
  await loadBatches()
  ElMessage.success('已关闭')
}

async function handleCancel(row) {
  try {
    await ElMessageBox.confirm(`确定取消设备“${row.equipmentName}”的这个批次吗？`, '取消批次', {
      type: 'warning',
      confirmButtonText: '取消批次',
      cancelButtonText: '返回',
    })
  } catch {
    return
  }
  actionBatchId.value = row.id
  const result = await cancelReservationBatch(row.id)
  actionBatchId.value = ''
  if (result.kind === 'unauthorized') return
  if (result.kind !== 'ok') {
    showError(result)
    return
  }
  await loadBatches()
  ElMessage.success('已取消')
}

const BATCH_STATUS_ACTION_HANDLERS = {
  edit: openEditDialog,
  publish: handlePublish,
  close: handleClose,
  cancel: handleCancel,
}

function handleBatchAction(row, action) {
  return BATCH_STATUS_ACTION_HANDLERS[action]?.(row)
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

watch(() => route.query, loadBatches, { immediate: true })
onMounted(loadLaboratoryOptions)
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

.full-width {
  width: 100%;
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
