<template>
  <section class="admin-equipment-page">
    <PageHeader title="我的设备" description="维护设备资料、预约开关与运行状态。">
      <template #extra>
        <el-button type="primary" @click="openCreateDialog">新建设备</el-button>
      </template>
    </PageHeader>

    <div v-loading="state === 'loading'" class="table-panel">
      <EmptyState v-if="state === 'error'" :title="`加载失败：${errorMessage}`">
        <template #action>
          <el-button type="primary" @click="loadEquipment">重试</el-button>
        </template>
      </EmptyState>

      <EmptyState v-else-if="state === 'ready' && records.length === 0" title="暂无设备数据">
        <template #action>
          <el-button type="primary" @click="openCreateDialog">新建设备</el-button>
        </template>
      </EmptyState>

      <template v-else-if="state === 'ready'">
        <el-table :data="records" border>
          <el-table-column prop="name" label="设备名称" min-width="170" />
          <el-table-column prop="laboratoryName" label="所属实验室" min-width="170" />
          <el-table-column prop="categoryName" label="分类" min-width="120" />
          <el-table-column prop="location" label="位置" min-width="130" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <StatusTag enum-name="EquipmentStatus" :value="row.status" />
            </template>
          </el-table-column>
          <el-table-column label="操作" min-width="350" align="center" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEditDialog(row)">编辑</el-button>
              <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
              <el-button
                v-for="action in equipmentActions(row)"
                :key="action"
                link
                type="primary"
                :loading="statusChangingId === row.id"
                @click="handleStatusChange(row, action)"
              >
                {{ EQUIPMENT_STATUS_ACTION_LABELS[action] }}
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

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑设备' : '新建设备'" width="620px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="102px">
        <el-form-item label="所属实验室" prop="laboratoryId">
          <el-select v-model="form.laboratoryId" class="full-width" filterable>
            <el-option v-for="item in laboratoryOptions" :key="item.id" :label="item.name" :value="String(item.id)" />
          </el-select>
        </el-form-item>
        <el-form-item label="分类" prop="categoryId">
          <el-select v-model="form.categoryId" class="full-width" filterable>
            <el-option v-for="item in categoryOptions" :key="item.id" :label="item.name" :value="String(item.id)" />
          </el-select>
        </el-form-item>
        <el-form-item label="设备名称" prop="name">
          <el-input v-model="form.name" maxlength="100" />
        </el-form-item>
        <el-form-item label="位置" prop="location">
          <el-input v-model="form.location" maxlength="200" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="图片" prop="image">
          <el-input v-model="form.image" placeholder="图片地址" />
        </el-form-item>
        <el-form-item label="使用要求" prop="usageRequirement">
          <el-input v-model="form.usageRequirement" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-select v-model="form.status" class="full-width">
            <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="开放预约" prop="bookingEnabled">
          <el-switch v-model="form.bookingEnabled" />
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

import {
  createEquipment,
  deleteEquipment,
  fetchEquipmentCategories,
  fetchEquipmentPage,
  updateEquipment,
  updateEquipmentStatus,
} from '../../../api/equipment.js'
import { fetchLaboratoryList } from '../../../api/laboratory.js'
import EmptyState from '../../../components/EmptyState.vue'
import PageHeader from '../../../components/PageHeader.vue'
import StatusTag from '../../../components/StatusTag.vue'
import { ENUM_VALUES } from '../../../constants/enums.js'
import { PAGE_SIZE, lastPageOf } from '../../../constants/pagination.js'
import { resolveStatus } from '../../../constants/statusMap.js'
import { buildEquipmentQuery } from '../../../utils/query.js'

const EQUIPMENT_STATUS_ACTION_TARGETS = {
  enable: 'AVAILABLE',
  maintain: 'MAINTENANCE',
  disable: 'DISABLED',
}
const EQUIPMENT_STATUS_ACTION_LABELS = {
  enable: '设为可用',
  maintain: '设为维护中',
  disable: '停用',
}

const route = useRoute()
const router = useRouter()
const state = ref('loading')
const records = ref([])
const total = ref(0)
const errorMessage = ref('')
const baseLaboratoryOptions = ref([])
const categoryOptions = ref([])
const editingLaboratoryOption = ref(null)
const dialogVisible = ref(false)
const editingId = ref('')
const submitting = ref(false)
const statusChangingId = ref('')
const formRef = ref()
const params = computed(() => buildEquipmentQuery(route.query))
const statusOptions = ENUM_VALUES.EquipmentStatus.map((value) => ({
  value,
  label: resolveStatus('EquipmentStatus', value).label,
}))
// 后端实验室列表固定每页 5 条，编辑时把当前行的实验室并入选项，避免显示裸 id。
const laboratoryOptions = computed(() => {
  const options = [...baseLaboratoryOptions.value]
  const current = editingLaboratoryOption.value
  if (current && !options.some((item) => String(item.id) === String(current.id))) options.push(current)
  return options
})
const form = reactive(emptyForm())
const rules = {
  laboratoryId: [{ required: true, message: '请选择所属实验室', trigger: 'change' }],
  categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }],
  name: [{ required: true, message: '请输入设备名称', trigger: 'blur' }],
}
let requestSequence = 0

function emptyForm() {
  return {
    laboratoryId: '',
    categoryId: '',
    name: '',
    location: '',
    description: '',
    image: '',
    usageRequirement: '',
    status: 'AVAILABLE',
    bookingEnabled: true,
  }
}

function showError(result) {
  ElMessage.error(result.message)
}

function equipmentActions(row) {
  return resolveStatus('EquipmentStatus', row.status).actions
}

async function loadEquipment() {
  const sequence = ++requestSequence
  state.value = 'loading'
  errorMessage.value = ''
  const result = await fetchEquipmentPage(params.value)
  if (sequence !== requestSequence) return
  if (result.kind === 'unauthorized') {
    errorMessage.value = result.message
    state.value = 'error'
    return
  }
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

async function loadOptions() {
  const [laboratoryResult, categoryResult] = await Promise.all([
    fetchLaboratoryList({ current: 1 }),
    fetchEquipmentCategories(),
  ])
  if (laboratoryResult.kind !== 'unauthorized') {
    if (laboratoryResult.kind === 'ok') baseLaboratoryOptions.value = Array.isArray(laboratoryResult.data) ? laboratoryResult.data : []
    else showError(laboratoryResult)
  }
  if (categoryResult.kind !== 'unauthorized') {
    if (categoryResult.kind === 'ok') categoryOptions.value = Array.isArray(categoryResult.data) ? categoryResult.data : []
    else showError(categoryResult)
  }
}

function resetForm(values = {}) {
  Object.assign(form, emptyForm(), values)
  nextTick(() => formRef.value?.clearValidate())
}

function openCreateDialog() {
  editingId.value = ''
  editingLaboratoryOption.value = null
  resetForm()
  dialogVisible.value = true
}

function openEditDialog(row) {
  editingId.value = row.id
  editingLaboratoryOption.value = { id: row.laboratoryId, name: row.laboratoryName }
  resetForm({
    laboratoryId: String(row.laboratoryId ?? ''),
    categoryId: String(row.categoryId ?? ''),
    name: row.name ?? '',
    location: row.location ?? '',
    description: row.description ?? '',
    image: row.image ?? '',
    usageRequirement: row.usageRequirement ?? '',
    status: row.status ?? 'AVAILABLE',
    bookingEnabled: row.bookingEnabled ?? true,
  })
  dialogVisible.value = true
}

async function handleSave() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  const payload = { ...form }
  const result = editingId.value
    ? await updateEquipment({ ...payload, id: editingId.value })
    : await createEquipment(payload)
  submitting.value = false
  if (result.kind === 'unauthorized') return
  if (result.kind !== 'ok') {
    showError(result)
    return
  }
  await loadEquipment()
  dialogVisible.value = false
  ElMessage.success(editingId.value ? '已保存' : '已创建')
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除设备“${row.name}”吗？`, '删除设备', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  const result = await deleteEquipment(row.id)
  if (result.kind === 'unauthorized') return
  if (result.kind !== 'ok') {
    showError(result)
    return
  }
  await loadEquipment()
  ElMessage.success('已删除')
}

async function handleStatusChange(row, action) {
  const target = EQUIPMENT_STATUS_ACTION_TARGETS[action]
  statusChangingId.value = row.id
  const result = await updateEquipmentStatus(row.id, target)
  statusChangingId.value = ''
  if (result.kind === 'unauthorized') return
  if (result.kind !== 'ok') {
    showError(result)
    return
  }
  await loadEquipment()
  ElMessage.success('状态已更新')
}

function changePage(current) {
  router.replace({ query: { ...route.query, current: String(current) } })
}

watch(() => route.query, loadEquipment, { immediate: true })
onMounted(loadOptions)
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

.full-width {
  width: 100%;
}

@media (max-width: 560px) {
  .pagination {
    justify-content: center;
  }
}
</style>
