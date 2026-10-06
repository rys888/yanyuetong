<template>
  <main class="login-page">
    <section class="login-panel" aria-labelledby="login-title">
      <div class="identity-mark" aria-hidden="true">
        <el-icon :size="28"><School /></el-icon>
      </div>

      <header class="login-heading">
        <h1 id="login-title">研约通</h1>
        <p>高校实验资源预约平台</p>
      </header>

      <el-form label-position="top" @submit.prevent="submitLogin">
        <el-form-item label="手机号">
          <el-input
            v-model="phone"
            inputmode="tel"
            maxlength="11"
            autocomplete="tel"
            placeholder="请输入手机号"
            clearable
          />
        </el-form-item>

        <el-form-item label="验证码">
          <div class="code-row">
            <el-input
              v-model="code"
              inputmode="numeric"
              maxlength="6"
              autocomplete="one-time-code"
              placeholder="请输入 6 位验证码"
            />
            <el-button
              class="code-button"
              :loading="sendingCode"
              :disabled="sendingCode || countdown > 0"
              @click="sendCode"
            >
              {{ countdown > 0 ? `重新获取(${countdown}s)` : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>

        <el-button
          class="login-button"
          type="primary"
          native-type="submit"
          :loading="submitting"
          :disabled="submitting"
        >
          登录
        </el-button>
      </el-form>

      <p v-if="devHint" class="dev-hint">{{ devHint }}</p>
    </section>
  </main>
</template>

<script setup>
import { onBeforeUnmount, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { School } from '@element-plus/icons-vue'

import { sendVerificationCode } from '../../api/user.js'
import { defaultRouteFor, sanitizeRedirect } from '../../router/guard.js'
import { useUserStore } from '../../stores/user.js'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const phone = ref('')
const code = ref('')
const sendingCode = ref(false)
const submitting = ref(false)
const countdown = ref(0)
const devHint = import.meta.env.DEV
  ? ['开发环境：验证码见', '后端控制台日志'].join('')
  : ''

let countdownTimer = null

function showResultError(result) {
  if (result.kind !== 'unauthorized') {
    ElMessage.error(result.message)
  }
}

function startCountdown() {
  countdown.value = 60
  countdownTimer = window.setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      window.clearInterval(countdownTimer)
      countdownTimer = null
    }
  }, 1000)
}

async function sendCode() {
  if (sendingCode.value || countdown.value > 0) {
    return
  }

  const normalizedPhone = phone.value.trim()
  if (!normalizedPhone) {
    ElMessage.warning('请输入手机号')
    return
  }

  sendingCode.value = true
  const result = await sendVerificationCode(normalizedPhone)
  sendingCode.value = false

  if (result.kind === 'ok') {
    ElMessage.success('验证码已发送')
    startCountdown()
    return
  }

  showResultError(result)
}

async function submitLogin() {
  if (submitting.value) {
    return
  }

  const normalizedPhone = phone.value.trim()
  const normalizedCode = code.value.trim()
  if (!normalizedPhone) {
    ElMessage.warning('请输入手机号')
    return
  }
  if (!normalizedCode) {
    ElMessage.warning('请输入验证码')
    return
  }

  submitting.value = true
  const loginResult = await userStore.login({
    phone: normalizedPhone,
    code: normalizedCode,
  })

  if (loginResult.kind !== 'ok') {
    submitting.value = false
    showResultError(loginResult)
    return
  }

  const profileResult = await userStore.fetchMe()
  submitting.value = false
  if (profileResult.kind !== 'ok') {
    return
  }

  const redirect = sanitizeRedirect(route.query.redirect)
  await router.replace(redirect || defaultRouteFor(userStore.profile?.role))
}

onBeforeUnmount(() => {
  if (countdownTimer !== null) {
    window.clearInterval(countdownTimer)
  }
})
</script>

<style scoped>
.login-page {
  display: grid;
  min-height: 100vh;
  place-items: center;
  padding: 32px 18px;
  background: linear-gradient(145deg, #f5f7fa 0%, #edf3f7 100%);
}

.login-panel {
  width: min(100%, 420px);
  padding: 34px 36px 30px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #fff;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}

.identity-mark {
  display: grid;
  width: 48px;
  height: 48px;
  margin: 0 auto 16px;
  place-items: center;
  border: 1px solid #d5dfe8;
  border-radius: 4px;
  background: #eaf0f4;
  color: #2c5f8d;
}

.login-heading {
  margin-bottom: 28px;
  text-align: center;
}

.login-heading h1 {
  margin: 0;
  color: #234c71;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: 0.08em;
  line-height: 1.35;
}

.login-heading p {
  margin: 7px 0 0;
  color: #606266;
  font-size: 14px;
}

.code-row {
  display: grid;
  width: 100%;
  grid-template-columns: minmax(0, 1fr) 132px;
  gap: 10px;
}

.code-button {
  width: 132px;
}

.login-button {
  width: 100%;
  margin-top: 8px;
}

.dev-hint {
  margin: 18px 0 0;
  color: #a8abb2;
  font-size: 12px;
  line-height: 1.5;
  text-align: center;
}

@media (max-width: 480px) {
  .login-panel {
    padding: 28px 22px 24px;
  }

  .code-row {
    grid-template-columns: minmax(0, 1fr) 118px;
  }

  .code-button {
    width: 118px;
    padding: 8px;
  }
}
</style>
