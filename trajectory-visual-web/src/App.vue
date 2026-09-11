<template>
  <el-container style="height: 100%">
    <el-aside width="210px" class="aside">
      <div class="brand">
        <div class="brand-title">LogiCompress</div>
        <div class="brand-sub">轨迹压缩可视化评估系统</div>
      </div>
      <el-menu :default-active="activePath" router class="menu">
        <template v-for="g in groups" :key="g.name">
          <div class="group-title">{{ g.name }}</div>
          <el-menu-item v-for="r in g.items" :key="r.path" :index="r.path">
            <el-icon><component :is="r.icon" /></el-icon>
            <span>{{ r.title }}</span>
          </el-menu-item>
        </template>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <div class="header-title">面向大宗物流的轨迹停留语义保持与分级压缩 · 可视化验证系统</div>
        <el-tag type="info" effect="plain" size="small">在线化验证（答辩演示用）</el-tag>
      </el-header>
      <el-main class="main-body">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const activePath = computed(() => route.path)

const menu = [
  { path: '/workbench', title: '轨迹压缩工作台', group: '在线演示', icon: 'MapLocation' },
  { path: '/timewindow', title: '时间窗口部分检索', group: '在线演示', icon: 'Timer' },
  { path: '/dashboard', title: '实验评估中心', group: '第6章评估', icon: 'DataAnalysis' },
  { path: '/compare', title: '算法压缩效果对比', group: '第6章评估', icon: 'Histogram' },
  { path: '/ablation', title: '消融与参数敏感性', group: '第6章评估', icon: 'Odometer' }
]
const groups = computed(() => {
  const map: any = {}
  menu.forEach((m) => {
    if (!map[m.group]) map[m.group] = { name: m.group, items: [] }
    map[m.group].items.push(m)
  })
  return Object.values(map)
})
</script>

<style scoped>
.aside { background: #0b2b4a; color: #cfd8e3; }
.brand { padding: 16px 12px 10px; color: #fff; }
.brand-title { font-size: 18px; font-weight: 700; letter-spacing: 1px; color: #eaf3fb; }
.brand-sub { font-size: 11px; color: #8fb3d1; margin-top: 2px; }
.menu { border-right: none; background: transparent; --el-menu-text-color: #b9c9d9; --el-menu-active-color: #fff; --el-menu-hover-bg-color: #123a5f; --el-menu-bg-color: transparent; }
.group-title { font-size: 11px; color: #6f8aa3; padding: 10px 20px 2px; }
.el-menu-item { height: 40px; }
.header { background: #fff; border-bottom: 1px solid #e6e9ee; display: flex; align-items: center; justify-content: space-between; }
.header-title { font-size: 15px; font-weight: 600; color: #1c3a57; }
.main-body { padding: 12px; overflow: auto; }
</style>
