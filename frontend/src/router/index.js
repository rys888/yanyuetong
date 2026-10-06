import { createRouter, createWebHistory } from 'vue-router'

import SystemCheck from '../views/SystemCheck.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      component: SystemCheck,
    },
  ],
})

export default router
