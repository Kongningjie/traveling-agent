<script setup lang="ts">
import { onMounted, ref } from 'vue'

interface SystemInfo {
  application: string
  mode: string
  modules: string[]
  agentStatus: string
  storageStatus: string
  externalCallsEnabled: boolean
}

const info = ref<SystemInfo | null>(null)
const loading = ref(false)
const error = ref('')

async function refresh() {
  loading.value = true
  error.value = ''
  info.value = null
  try {
    const response = await fetch('/api/v1/system/info', { signal: AbortSignal.timeout(5000) })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    info.value = await response.json() as SystemInfo
  } catch {
    error.value = '暂时无法连接后端，请确认后端已启动并检查代理配置。'
  } finally {
    loading.value = false
  }
}

onMounted(refresh)
</script>

<template>
  <main>
    <header><span class="brand">旅行助手</span><span class="badge">开发骨架 · M0</span></header>
    <section class="intro">
      <p class="eyebrow">从一个想法，到一段旅程</p>
      <h1>一起把旅程安排好。</h1>
      <p>这里将承载需求协商、行程规划与版本确认。当前先验证前后端工程接线。</p>
    </section>
    <section class="panel" aria-live="polite">
      <div class="panel-heading"><h2>工程连接状态</h2><button :disabled="loading" @click="refresh">{{ loading ? '连接中…' : '刷新状态' }}</button></div>
      <p v-if="error" class="error">{{ error }}</p>
      <template v-else-if="info">
        <p class="connected">后端已连接 · {{ info.mode }}</p>
        <dl>
          <div><dt>Agent 接入</dt><dd>{{ info.agentStatus === 'HARNESS_NOT_VALIDATED' ? '依赖已接入，真实调用待验证' : info.agentStatus }}</dd></div>
          <div><dt>存储接入</dt><dd>{{ info.storageStatus === 'NOT_VERIFIED' ? '数据库与会话持久化待验证' : info.storageStatus }}</dd></div>
          <div><dt>外部调用</dt><dd>{{ info.externalCallsEnabled ? '已开启' : '未开启' }}</dd></div>
        </dl>
        <div class="modules"><span v-for="module in info.modules" :key="module">{{ module }}</span></div>
      </template>
      <p v-else>正在检查后端连接…</p>
    </section>
    <section class="next">
      <article><span>01</span><h2>说出旅行想法</h2><p>通过建议与选择共同明确需求。</p><small>业务功能待实现</small></article>
      <article><span>02</span><h2>形成可核实的行程</h2><p>结合证据调查、约束校验与独立审核。</p><small>业务功能待实现</small></article>
      <article><span>03</span><h2>确认与调整</h2><p>比较候选版本，由你决定是否接受。</p><small>业务功能待实现</small></article>
    </section>
    <footer>当前页面不会发起模型、地图或检索服务调用。</footer>
  </main>
</template>
