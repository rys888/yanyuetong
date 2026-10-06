import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'

import App from './App.vue'
import { setUnauthorizedHandler } from './api/request.js'
import router from './router/index.js'
import { useUserStore } from './stores/user.js'
import './assets/base.css'

export function handleUnauthorized() {
  const store = useUserStore()
  const wasLoggedIn = Boolean(store.token)
  store.reset()
  if (wasLoggedIn) {
    router.replace({
      path: '/login',
      query: { redirect: router.currentRoute.value.fullPath },
    })
  }
}

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ElementPlus)

setUnauthorizedHandler(handleUnauthorized)

app.mount('#app')
