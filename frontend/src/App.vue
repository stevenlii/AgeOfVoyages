<script setup>
import { computed, ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { game, portName, askPlayerName, sendChat } from './game'

const router = useRouter()
const chatText = ref('')

function cargoUsed() {
  const c = game.you?.cargo || {}
  return Object.values(c).reduce((a, b) => a + b, 0)
}

const statusText = computed(() => {
  const y = game.you
  if (!y) return '连接中…'
  const sailing = game.voyage
    ? ` ｜ ⛵ 已航行 ${game.voyage.traveledKm}/${game.voyage.totalKm} 公里`
    : y.traveling ? ' ｜ ⛵ 航行中 ｜ 请到「航海图」继续' : ''
  return `👤 ${y.name} ｜ 💰 ${y.gold} ｜ 📍 ${portName(y.port)} ｜ 📦 舱位 ${cargoUsed()}/${y.cargoCap}${sailing}`
})

function goMap() {
  if (game.voyage) { router.push('/sail'); return }
  router.push('/map')
}

function doChat() {
  const t = chatText.value.trim()
  if (t) { sendChat(t); chatText.value = '' }
}

onMounted(() => askPlayerName())
</script>

<template>
  <div class="wrap">
    <h1>⚓ 纵横四海 · 文字跑商</h1>

    <div class="panel status">{{ statusText }}</div>
    <div class="nav">
      <router-link to="/">🏝 港口市场</router-link>
      <a href="#" @click.prevent="goMap">🗺 航海图</a>
      <router-link v-if="game.voyage" to="/sail">
        ⛵ {{ game.voyage.departed ? `继续航行（${game.voyage.clicks - game.voyage.clicked} 次）` : '查看航线（未出发）' }}
      </router-link>
    </div>

    <div v-if="game.toast" class="toast">🔔 {{ game.toast }}</div>

    <router-view />

    <div class="panel">
      <b>💬 海域频道</b>
      <div id="log">
        <div v-for="(l, i) in game.logs" :key="i" class="logline">{{ l }}</div>
      </div>
      <div class="chatrow">
        <input v-model="chatText" placeholder="说点什么…" @keyup.enter="doChat" />
        <button @click="doChat">发送</button>
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
  .status { font-weight:bold; }
  .nav { margin:8px 0; }
  .nav a { color:#9fc0ff; text-decoration:none; margin-right:16px; font-size:15px; }
  .nav a:hover { color:#cfe0ff; text-decoration:underline; }
  table { width:100%; border-collapse:collapse; margin-top:8px; }
  th, td { padding:6px; border-bottom:1px solid #1c2742; text-align:left; font-size:14px; }
  button { background:#1c2b4a; color:#e8e6d9; border:1px solid #34507f; border-radius:6px; padding:6px 10px; cursor:pointer; margin:3px 2px; }
  button:hover { background:#27406e; }
  input { background:#0b1020; color:#e8e6d9; border:1px solid #34507f; border-radius:4px; padding:4px; width:54px; }
  .chatrow input { width:70%; }
  #log { height:160px; overflow:auto; font-size:13px; line-height:1.6; margin-bottom:6px; }
  .logline { padding:1px 0; }
  .toast { background:#1c2b4a; border:1px solid #34507f; border-radius:6px; padding:8px 12px; margin:8px 0; color:#ffd479; }
  .up { color:#ff6b6b; }
  .down { color:#4ecb73; }
  .flat { color:#8899aa; }
</style>