<template>
  <section class="admin-laboratory-page">
    <PageHeader title="我的实验室" description="维护实验室信息与开放状态。">
      <template #extra>
        <el-button type="primary" @click="openCreateDialog">新建实验室</el-button>
      </template>
    </PageHeader>

    <div v-loading="state === 'loading'" class="table-panel">
      <EmptyState v-if="state === 'error'" :title="`加载失败：${errorMessage}`">
        <template #action>
          <el-button type="primary" @click="loadLaboratories">重试</el-button>
        </template>
      </EmptyState>

      <EmptyState
        v-else-if="state === 'ready' && records.length === 0"
        title="暂无实验室数据"
      >
        <template #action>
          <el-button type="primary" @click="openCreateDialog">新建实验室</el-button>
        </template>
      </EmptyState>

      <template v-else-if="state === 'ready'">
        <el-table :data="records" border>
          <el-table-column prop="name" label="名称" min-width="180" />
          <el-table-column prop="campus" label="校区" min-width="130" />
          <el-table-column prop="building" label="楼栋" min-width="120" />
          <el-table-column prop="room" label="房间" min-width="110" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <StatusTag enum-name="LaboratoryStatus" :value="row.status" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" align="center" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEditDialog(row)">编辑</el-button>
              <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
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

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑实验室' : '新建实验室'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="100" />
        </el-form-item>
        <el-form-item label="校区" prop="campus">
          <el-input v-model="form.campus" maxlength="100" />
        </el-form-item>
        <el-form-item label="楼栋" prop="building">
          <el-input v-model="form.building" maxlength="100" />
        </el-form-item>
        <el-form-item label="房间" prop="room">
          <el-input v-model="form.room" maxlength="100" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-select v-model="form.status" class="full-width">
            <el-option
              v-for="item in statusOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
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
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import {
  createLaboratory,
  deleteLaboratory,
  fetchLaboratoryList,
  updateLaboratory,
} from '../../../api/laboratory.js'
import EmptyState from '../../../components/EmptyState.vue'
import PageHeader from '../../../components/PageHeader.vue'
import StatusTag from '../../../components/StatusTag.vue'
import { ENUM_VALUES } from '../../../constants/enums.js'
import { PAGE_SIZE, lastPageOf } from '../../../constants/pagination.js'
import { resolveStatus } from '../../../constants/statusMap.js'
import { buildLaboratoryQuery } from '../../../utils/query.js'

const route = useRoute()
const router = useRouter()
const state = ref('loading')
const records = ref([])
const total = ref(0)
const errorMessage = ref('')
const dialogVisible = ref(false)
const editingId = ref('')
const submitting = ref(false)
const formRef = ref()
const params = computed(() => buildLaboratoryQuery(route.query))
const statusOptions = ENUM_VALUES.LaboratoryStatus.map((value) => ({
  value,
  label: resolveStatus('LaboratoryStatus', value).label,
}))
const form = reactive(emptyForm())
const rules = {
  name: [{ required: true, message: '请输入实验室名称', trigger: 'blur' }],
}
let requestSequence = 0

function emptyForm() {
  return {
    name: '',
    campus: '',
    building: '',
    room: '',
    description: '',
    status: 'OPEN',
  }
}

function showError(result) {
  ElMessage.error(result.message)
}

async function loadLaboratories() {
  const sequence = ++requestSequence
  state.value = 'loading'
  errorMessage.value = ''
  const result = await fetchLaboratoryList(params.value)
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

function resetForm(values = {}) {
  Object.assign(form, emptyForm(), values)
  nextTick(() => formRef.value?.clearValidate())
}

function openCreateDialog() {
  editingId.value = ''
  resetForm()
  dialogVisible.value = true
}

function openEditDialog(row) {
  editingId.value = row.id
  resetForm({
    name: row.name ?? '',
    campus: row.campus ?? '',
    building: row.building ?? '',
    room: row.room ?? '',
    description: row.description ?? '',
    status: row.status ?? 'OPEN',
  })
  dialogVisible.value = true
}

async function handleSave() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  const payload = { ...form }
  const result = editingId.value
    ? await updateLaboratory({ ...payload, id: editingId.value })
    : await createLaboratory(payload)
  submitting.value = false
  if (result.kind === 'unauthorized') return
  if (result.kind !== 'ok') {
    showError(result)
    return
  }
  await loadLaboratories()
  dialogVisible.value = false
  ElMessage.success(editingId.value ? '已保存' : '已创建')
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除实验室“${row.name}”吗？`, '删除实验室', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  const result = await deleteLaboratory(row.id)
  if (result.kind === 'unauthorized') return
  if (result.kind !== 'ok') {
    showError(result)
    return
  }
  await loadLaboratories()
  ElMessage.success('已删除')
}

function changePage(current) {
  router.replace({ query: { ...route.query, current: String(current) } })
}

watch(() => route.query, loadLaboratories, { immediate: true })
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

@media (max-width: 600px) {
  .pagination {
    justify-content: center;
  }
}
</style>
