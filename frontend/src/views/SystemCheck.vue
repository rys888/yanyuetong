<template>
  <main class="system-check-page">
    <el-card class="system-check-card">
      <template #header>
        <h1>研约通 · 前端连通性自检</h1>
      </template>

      <p class="description">F0 阶段验证页：确认前端 dev server 能通过代理访问后端。</p>

      <el-form label-position="top" @submit.prevent>
        <el-form-item label="手机号">
          <el-input v-model="phone" inputmode="tel" autocomplete="tel" />
        </el-form-item>

        <el-button
          type="primary"
          :icon="Message"
          :loading="loading"
          :disabled="loading"
          @click="sendCode"
        >
          发送验证码
        </el-button>
      </el-form>

      <section class="result" aria-live="polite">
        <h2>测试结果</h2>

        <el-tag v-if="status === 'idle'" type="info" effect="plain">未测试</el-tag>

        <template v-else-if="status === 'success'">
          <el-tag type="success" effect="plain">成功</el-tag>
          <p class="success-message">代理已打通：后端返回 success=true</p>
          <pre>{{ formattedResult }}</pre>
        </template>

        <template v-else>
          <el-tag type="danger" effect="plain">失败</el-tag>
          <p class="failure-message">{{ failureMessage }}</p>
        </template>
      </section>
    </el-card>
  </main>
</template>

<script setup>
import { computed, ref } from 'vue'
import { Message } from '@element-plus/icons-vue'

import { sendVerificationCode } from '../api/user.js'

const phone = ref('13686869696')
const loading = ref(false)
const status = ref('idle')
const result = ref(null)
const failureMessage = ref('')

const formattedResult = computed(() => JSON.stringify(result.value, null, 2))

async function sendCode() {
  if (loading.value) {
    return
  }

  loading.value = true
  status.value = 'idle'
  result.value = null
  failureMessage.value = ''

  try {
    const responseResult = await sendVerificationCode(phone.value)

    if (responseResult?.ok) {
      result.value = responseResult
      status.value = 'success'
      return
    }

    status.value = 'failure'
    failureMessage.value = responseResult?.message || '后端响应格式异常'
  } catch (error) {
    status.value = 'failure'
    failureMessage.value = error?.message || '请求失败'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.system-check-page {
  display: flex;
  justify-content: center;
  box-sizing: border-box;
  min-height: 100vh;
  padding: 64px 20px;
}

.system-check-card {
  width: min(100%, 640px);
  height: fit-content;
}

h1,
h2,
p {
  margin-top: 0;
}

h1 {
  margin-bottom: 0;
  font-size: 22px;
  line-height: 1.4;
}

.description {
  margin-bottom: 24px;
  color: var(--el-text-color-regular);
  line-height: 1.6;
}

.result {
  margin-top: 28px;
  padding-top: 24px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.result h2 {
  margin-bottom: 14px;
  font-size: 16px;
}

.success-message,
.failure-message {
  margin: 14px 0 0;
  line-height: 1.6;
}

.success-message {
  color: var(--el-color-success);
}

.failure-message {
  color: var(--el-color-danger);
  overflow-wrap: anywhere;
}

pre {
  overflow-x: auto;
  margin: 14px 0 0;
  padding: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--el-border-radius-base);
  background: var(--el-fill-color-lighter);
  color: var(--el-text-color-primary);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

@media (max-width: 480px) {
  .system-check-page {
    padding: 24px 12px;
  }
}
</style>
