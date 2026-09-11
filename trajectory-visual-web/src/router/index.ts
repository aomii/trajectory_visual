import { createRouter, createWebHashHistory, RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/dashboard'
  },
  {
    path: '/workbench',
    name: 'workbench',
    component: () => import('../views/WorkbenchView.vue'),
    meta: { title: '轨迹压缩工作台', group: '在线演示' }
  },
  {
    path: '/timewindow',
    name: 'timewindow',
    component: () => import('../views/TimeWindowView.vue'),
    meta: { title: '时间窗口部分检索', group: '在线演示' }
  },
  {
    path: '/dashboard',
    name: 'dashboard',
    component: () => import('../views/DashboardView.vue'),
    meta: { title: '实验评估中心', group: '第6章评估' }
  },
  {
    path: '/compare',
    name: 'compare',
    component: () => import('../views/CompareView.vue'),
    meta: { title: '算法压缩效果对比', group: '第6章评估' }
  },
  {
    path: '/ablation',
    name: 'ablation',
    component: () => import('../views/AblationParamView.vue'),
    meta: { title: '消融与参数敏感性', group: '第6章评估' }
  }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
