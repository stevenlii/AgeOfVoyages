import { reactive } from 'vue'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'

// ---------- 全局状态（单例）：跨页面共享，刷新后从 localStorage 恢复身份 ----------
export const game = reactive({
  connected: false,
  you: null,
  market: {},
  goods: [],
  ports: [],
  regions: [],
  voyage: null,
  toast: '',
  logs: [],
  forecast: [],
  forecastFrom: '',
})

let client = null
let toastTimer = null

function stored(key, fallback) {
  try {
    const v = localStorage.getItem(key)
    return v && v.length ? v : fallback
  } catch {
    return fallback
  }
}

// 身份在一个浏览器里固定下来：不再每次刷新生成新玩家
export const clientId = stored('aov_clientId', crypto.randomUUID())
let playerName = stored('aov_name', '')

function save(key, value) {
  try { localStorage.setItem(key, value) } catch { /* ignore */ }
}

export function getPlayerName() {
  return playerName
}

export function askPlayerName() {
  if (!playerName) {
    playerName = window.prompt('为你的船长起一个名字：') || '无名船长'
  }
  save('aov_name', playerName)
  if (playerName && client && client.connected) {
    client.publish({
      destination: '/app/login',
      body: JSON.stringify({ clientId, name: playerName }),
    })
  }
  return playerName
}

function toast(text) {
  game.toast = text
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { game.toast = '' }, 4000)
}

export function init() {
  if (client) return
  save('aov_clientId', clientId)
  client = new Client({
    webSocketFactory: () => new SockJS('/ws'),
    reconnectDelay: 5000,
    onConnect: () => {
      game.connected = true
      client.subscribe(`/topic/player/${clientId}/state`, (m) => {
        const s = JSON.parse(m.body)
        game.you = s.you
        game.market = s.market
        game.goods = s.goods
        game.ports = s.ports
        game.regions = s.regions
        game.voyage = s.voyage
      })
      client.subscribe(`/topic/player/${clientId}/msg`, (m) => {
        toast(JSON.parse(m.body).text)
      })
      client.subscribe('/topic/log', (m) => {
        game.logs.push(JSON.parse(m.body).text)
        while (game.logs.length > 50) game.logs.shift()
      })
      client.subscribe(`/topic/player/${clientId}/forecast`, (m) => {
        const f = JSON.parse(m.body)
        game.forecastFrom = f.from
        game.forecast = f.days
      })
      client.publish({
        destination: '/app/login',
        body: JSON.stringify({ clientId, name: playerName }),
      })
    },
    onWebSocketClose: () => { game.connected = false },
    onStompError: (f) => { game.connected = false; toast('连接错误：' + (f.headers?.message || '')) },
  })
  client.activate()
}

export function startVoyage(to) {
  client?.publish({
    destination: '/app/travel',
    body: JSON.stringify({ clientId, to }),
  })
}

export function sail(dir = 'forward') {
  client?.publish({
    destination: '/app/sail',
    body: JSON.stringify({ clientId, dir }),
  })
}

export function decide(choice) {
  client?.publish({
    destination: '/app/sailDecision',
    body: JSON.stringify({ clientId, choice }),
  })
}

export function forecast() {
  client?.publish({
    destination: '/app/forecast',
    body: JSON.stringify({ clientId }),
  })
}

export function trade(action, item, count) {
  client?.publish({
    destination: '/app/trade',
    body: JSON.stringify({ clientId, action, item, count }),
  })
}

export function sendChat(text) {
  client?.publish({
    destination: '/app/chat',
    body: JSON.stringify({ clientId, text }),
  })
}

// ---------- 工具 ----------

export function portName(id) {
  const p = game.ports.find((x) => x.id === id)
  return p ? p.name : id
}

export function goodName(id) {
  const g = game.goods.find((x) => x.id === id)
  return g ? g.name : id
}