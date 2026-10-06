export const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/login/LoginView.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/',
    component: () => import('../layouts/AppLayout.vue'),
    redirect: '/home',
    meta: { roles: ['USER', 'LAB_ADMIN'] },
    children: [
      {
        path: 'home',
        name: 'home',
        component: () => import('../views/home/HomeView.vue'),
        meta: { title: '首页', menu: { title: '首页', icon: 'HomeFilled', order: 1 } },
      },
      {
        path: 'laboratories',
        name: 'laboratory-list',
        component: () => import('../views/laboratory/LaboratoryListView.vue'),
        meta: {
          title: '实验室',
          menu: { title: '实验室', icon: 'School', order: 2 },
        },
      },
      {
        path: 'laboratories/:id',
        name: 'laboratory-detail',
        component: () => import('../views/laboratory/LaboratoryDetailView.vue'),
        meta: { title: '实验室详情' },
      },
      {
        path: 'equipment',
        name: 'equipment-list',
        component: () => import('../views/equipment/EquipmentListView.vue'),
        meta: {
          title: '设备',
          menu: { title: '设备', icon: 'Cpu', order: 3 },
        },
      },
      {
        path: 'equipment/:id',
        name: 'equipment-detail',
        component: () => import('../views/equipment/EquipmentDetailView.vue'),
        meta: { title: '设备详情' },
      },
      {
        path: 'batches',
        name: 'batch-list',
        component: () => import('../views/batch/BatchListView.vue'),
        meta: { title: '预约批次', menu: { title: '预约批次', icon: 'Calendar', order: 4 } },
      },
      {
        path: 'batches/:id',
        name: 'batch-detail',
        component: () => import('../views/batch/BatchDetailView.vue'),
        meta: { title: '批次详情' },
      },
    ],
  },
  {
    path: '/admin',
    component: () => import('../layouts/AdminLayout.vue'),
    meta: { roles: ['LAB_ADMIN'] },
    children: [
      {
        path: '',
        name: 'admin-home',
        component: () => import('../views/admin/AdminHomeView.vue'),
        meta: {
          title: '管理首页',
          menu: { title: '管理首页', icon: 'Odometer', order: 1 },
        },
      },
    ],
  },
  {
    path: '/403',
    name: 'forbidden',
    component: () => import('../views/error/ForbiddenView.vue'),
    meta: { public: true, title: '无权访问' },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('../views/error/NotFoundView.vue'),
    meta: { public: true, title: '页面不存在' },
  },
]
