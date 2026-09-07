<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'

const clientId = crypto.randomUUID()
const playerName = ref(prompt('输入你的船长名：') || '无名船长')

const state = ref(null)
const logs = ref([])
const toast = ref('')
let client = null
let toastTimer = null

function portName(id) {
  const p = (state.value?.ports || []).find((x) => x.id === id)
  return p ? p.name : id
}
function cargoUsed() {
  const c = state.value?.you?.cargo || {}
  return Object.values(c).reduce((a, b) => a + b, 0)
}
const statusText = computed(() => {
  const y = state.value?.you
  if (!y) return '连接中…'
  const t = y.traveling ? ` ｜ 🌊 航行至 ${portName(y.traveling.to)}…` : ''
  return `👤 ${y.name} ｜ 💰 ${y.gold} ｜ 📍 ${portName(y.port)} ｜ 📦 舱位 ${cargoUsed()}/${y.cargoCap}${t}`
})

// 行情涨跌（相对货物基准价）：涨红跌绿
function trendOf(id) {
  return state.value?.market?.[id]?.trend ?? 0
}
function trendText(t) {
  return t ? (t > 0 ? '↑' : '↓') + Math.abs(t) + '%' : '—'
}
function trendClass(t) {
  if (t >= 8) return 'up'
  if (t <= -8) return 'down'
  return 'flat'
}

function connect() {
  client = new Client({
    webSocketFactory: () => new SockJS('/ws'),
    reconnectDelay: 5000,
    onConnect: () => {
      client.subscribe(`/topic/player/${clientId}/state`, (m) => {
        state.value = JSON.parse(m.body)
      })
      client.subscribe(`/topic/player/${clientId}/msg`, (m) => {
        toast.value = JSON.parse(m.body).text
        clearTimeout(toastTimer)
        toastTimer = setTimeout(() => (toast.value = ''), 3000)
      })
      client.subscribe('/topic/log', (m) => {
        logs.value.push(JSON.parse(m.body).text)
        if (logs.value.length > 200) logs.value.shift()
      })
      client.publish({
        destination: '/app/login',
        body: JSON.stringify({ clientId, name: playerName.value })
      })
    }
  })
  client.activate()
}

function trade(action, item) {
  const q = document.getElementById('q_' + item)?.value || '1'
  client.publish({
    destination: '/app/trade',
    body: JSON.stringify({ clientId, action, item, count: q })
  })
}
function travel(to) {
  client.publish({ destination: '/app/travel', body: JSON.stringify({ clientId, to }) })
}
function sendChat() {
  const el = document.getElementById('chatText')
  const t = el?.value?.trim()
  if (t) {
    client.publish({ destination: '/app/chat', body: JSON.stringify({ clientId, text: t }) })
    el.value = ''
  }
}

onMounted(connect)
onUnmounted(() => client && client.deactivate())
</script>

<template>
  <div class="wrap">
    <h1>⚓ 纵横四海 · 文字跑商</h1>

    <div class="panel" :class="{ dim: !state }">{{ statusText }}</div>
    <div v-if="toast" class="toast">🔔 {{ toast }}</div>

    <div class="panel">
      <b>🏝 港口市场</b>
      <table v-if="state">
        <thead>
          <tr><th>货物</th><th>买入价</th><th>卖出价</th><th>相比平常</th><th>数量</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="g in state.goods" :key="g.id">
            <td>{{ g.name }}</td>
            <td>{{ state.market[g.id]?.buy }}</td>
            <td>{{ state.market[g.id]?.sell }}</td>
            <td :class="trendClass(trendOf(g.id))">{{ trendText(trendOf(g.id)) }}</td>
            <td><input :id="'q_' + g.id" value="1" /></td>
            <td>
              <button @click="trade('buy', g.id)">买</button>
              <button @click="trade('sell', g.id)">卖</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="panel">
      <b>⛵ 航行</b>
      <div v-if="state" class="row">
        <button
          v-for="p in state.ports.filter(p => p.id !== state.you.port)"
          :key="p.id"
          @click="travel(p.id)"
        >航向 {{ p.name }}（{{ p.travelTime }}s）</button>
      </div>
    </div>

    <div class="panel">
      <b>💬 海域频道</b>
      <div id="log">
        <div v-for="(l, i) in logs" :key="i" class="logline">{{ l }}</div>
      </div>
      <div class="chatrow">
        <input id="chatText" placeholder="说点什么…" @keyup.enter="sendChat" />
        <button @click="sendChat">发送</button>
      </div>
    </div>
  </div>
</template>

<style>
  body { background:#0b1020; color:#e8e6d9; font-family: ui-monospace, "SF Mono", Menlo, monospace; }
  .wrap { max-width:720px; margin:0 auto; padding:16px; }
  h1 { font-size:20px; margin:0 0 12px; }
  .panel { border:1px solid #2a3a5a; border-radius:8px; padding:12px; margin:10px 0; background:#0f1730; }
  .panel b { color:#9fc0ff; }
  .dim { opacity:.6; }
  table { width:100%; border-collapse:collapse; margin-top:8px; }
  th, td { padding:6px; border-bottom:1px solid #1c2742; text-align:left; font-size:14px; }
  button { background:#1c2b4a; color:#e8e6d9; border:1px solid #34507f; border-radius:6px; padding:6px 10px; cursor:pointer; margin:3px 2px; }
  button:hover { background:#27406e; }
  input { background:#0b1020; color:#e8e6d9; border:1px solid #34507f; border-radius:4px; padding:4px; width:54px; }
  .chatrow input { width:70%; }
  #log { height:160px; overflow:auto; font-size:13px; line-height:1.6; margin-bottom:6px; }
  .logline { padding:1px 0; }
  .toast { background:#1c2b4a; border:1px solid #34507f; border-radius:6px; padding:8px 12px; margin:8px 0; color:#ffd479; }
  /* 涨红跌绿（符合国内行情习惯） */
  .up { color:#ff6b6b; }
  .down { color:#4ecb73; }
  .flat { color:#8899aa; }
</style>
