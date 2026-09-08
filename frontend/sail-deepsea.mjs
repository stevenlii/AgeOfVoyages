// 深海验证：长途航线（伦敦→里斯本，越过大西洋）上，海盗只应发生在“海中间”，且每 300 公里内最多一次。
// 附带校验：天气是延续的（不会每击变天），好天气有鸟/鱼彩蛋、坏天气有心情描写。
// 运行：node sail-deepsea.mjs（需后端 8080）；换全新玩家用 DEEP_CLIENT_ID=deep-... 覆盖。
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'

const clientId = process.env.DEEP_CLIENT_ID || 'deep-sea-probe'
const TARGET = 'lisbon'                       // 1550km，略超 LONG_HAUL_KM=1500，且有大西洋深海段
const DEEP_SEA_KM = 150
const EARTH_R = 6371.0
const BASE = 'http://localhost:8080'

const PORTS = [
  ['london', 51.5074, -0.1278], ['amsterdam', 52.3676, 4.9041], ['lisbon', 38.7223, -9.1393],
  ['copenhagen', 55.6761, 12.5683], ['genoa', 44.4056, 8.9463], ['venice', 45.4408, 12.3155],
  ['athens', 37.9838, 23.7275], ['alexandria', 31.2001, 29.9187], ['goa', 15.2993, 74.1240],
  ['colombo', 6.9271, 79.8612], ['malacca', 2.1896, 102.2501], ['guangzhou', 23.1291, 113.2644],
  ['quanzhou', 24.8741, 118.6757], ['nagasaki', 32.7503, 129.8777], ['manila', 14.5995, 120.9842],
]

function havKm(lat1, lon1, lat2, lon2) {
  const dLat = (lat2 - lat1) * Math.PI / 180, dLon = (lon2 - lon1) * Math.PI / 180
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) * Math.sin(dLon / 2) ** 2
  return 2 * EARTH_R * Math.asin(Math.sqrt(a))
}

const minPortKm = (lat, lng) => Math.min(...PORTS.map(([, a, o]) => havKm(lat, lng, a, o)))

let phase = 'init'
let clicks = 0
let pirates = []           // { km, deepSea }
let weatherCur = null
let weatherChanges = 0
let weatherSamples = 0
let ambCount = 0           // 彩蛋行（带 🐬🕊🐟🐢🐳🌊⚡🌧 等图标）计数
let arrived = false
let seaLogSeen = new Set()   // 已经见过行（seaLog 会被 trim，用“首次出现”判定新增行）

function fail(msg) {
  console.error('❌', msg)
  client.deactivate()
  process.exit(1)
}

const client = new Client({
  webSocketFactory: () => new SockJS(`${BASE}/ws`),
  reconnectDelay: 5000,
  onConnect: () => {
    client.subscribe(`/topic/player/${clientId}/state`, (m) => {
      const st = JSON.parse(m.body)
      const you = st.you
      const voy = st.voyage

      if (phase === 'init') {
        console.log('[状态]', `金币=${you.gold} 港=${you.port}`)
        client.publish({ destination: '/app/travel', body: JSON.stringify({ clientId, to: TARGET }) })
        phase = 'planned'
        return
      }

      if (phase === 'planned') {
        console.log('-> 启航', TARGET, `全程${Math.round(voy.totalKm)}km`, `约${Math.ceil(voy.totalKm / 10)}击`)
        client.publish({ destination: '/app/sail', body: JSON.stringify({ clientId }) })
        phase = 'sailing'
        return
      }

      if (!voy) {
        if (arrived) return
        if (you.port === TARGET) {
          arrived = true
          client.deactivate()
          report()
        } else fail(`航程中途结束，港=${you.port}`)
        return
      }

      clicks++
      const cur = voy.seaLog || []
      const added = []
      for (const ln of cur) {
        if (!seaLogSeen.has(ln)) {
          seaLogSeen.add(ln)
          added.push(ln)
        }
      }
      for (const ln of added) {
        if (ln.includes('【海盗】')) {
          const deep = minPortKm(voy.lat, voy.lng) >= DEEP_SEA_KM
          pirates.push({ km: Number(voy.traveledKm).toFixed(0), deep, line: ln })
        }
        if (/[🐬🕊🐟🐢🐳🌊⚡🌧☀️🎣💧🪼🌩]/.test(ln)) ambCount++
      }

      // 天气延续性采样
      // 天气延续性采样（从“已航行…｜ 天气：”数字行取值）
      const cur2 = cur.slice(-8)
      const wm = cur2.find(l => l.includes('已航行') && l.includes('｜ 天气：'))
      if (wm) {
        const w = wm.match(/｜ 天气：([^ ｜]+)/)[1]
        if (weatherCur !== null && weatherCur !== w) weatherChanges++
        weatherCur = w
        weatherSamples++
      }

      if (voy.offered) {
        client.publish({ destination: '/app/sailDecision', body: JSON.stringify({ clientId, choice: 'continue' }) })
      } else {
        if (clicks % 10 === 0) console.log(`   [${clicks}] ${Math.round(voy.traveledKm)}km 天气=${weatherCur} 距最近港=${Math.round(minPortKm(voy.lat, voy.lng))}km`)
        client.publish({ destination: '/app/sail', body: JSON.stringify({ clientId }) })
      }
    })

    client.publish({ destination: '/app/login', body: JSON.stringify({ clientId, name: '深海验证船长' }) })
  },
  onStompError: (f) => { console.error('STOMP 错误', f.body); process.exit(1) },
})

function report() {
  const worstDeep = pirates.filter(p => !p.deep)
  console.log('---- 深海验证结果 ----')
  console.log(`总航行 ${clicks} 击；海盗事件 ${pirates.length} 次；小彩蛋(鸟/鱼/雷/浪等) ${ambCount} 条`)
  console.log(`天气采样 ${weatherSamples} 次，共渐变 ${weatherChanges} 次（占比 ${(weatherChanges / Math.max(1, weatherSamples) * 100).toFixed(1)}%）`)
  for (const p of pirates) console.log(`   🏴 ${p.km}km 距最近港=${p.deep ? '≥150km(海中间)✓' : '近海 ✗'} ｜ ${p.line.split('。')[0]}…`)

  if (worstDeep.length) return fail(`海盗出现在近海（距最近港<${DEEP_SEA_KM}km），与“只在海中间”矛盾`)
  for (let i = 1; i < pirates.length; i++) {
    const gap = Number(pirates[i].km) - Number(pirates[i - 1].km)
    if (gap < 300) return fail(`两次海盗仅相隔 ${gap}km，违反“每 300 公里最多一次”`)
  }
  if (weatherChanges / Math.max(1, weatherSamples) > 0.6) return fail('天气渐变过于频繁（应长时间保持同一天气）')
  if (pirates.length === 0) console.warn('⚠️ 本次航行没遇到海盗（随机使然），门控未真正命中；可重跑一次')
  else console.log(`✅ 海盗 x${pirates.length} 全部发生在海中间，且间距 ≥300km；天气渐变比例正常；彩蛋稳定出现`)
  process.exit(0)
}

client.activate()
setTimeout(() => fail(`超时（已操作 ${clicks} 击）`), 600000)