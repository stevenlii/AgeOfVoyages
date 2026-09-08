// 限流验证：一趟长途航线内，海盗≤2且间距≥1000km、漂流瓶≤5且间距≥600km且开局<100km不出现、
// 彩蛋≤10且间距≥300km、雷击≤1。按 voy.event 的“边沿变化”计数（每触发一次记一次，文本重复/seaLog trim 都不影响）。
// 运行前先把各事件 per_km 调大（如 0.9）让限流条件被顶满；结束还原。
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'

const clientId = process.env.CAP_CLIENT_ID || 'cap-probe'
const TARGET = 'colombo'
const BASE = 'http://localhost:8080'

const hits = { pirate: [], bottle: [], ambient: [], lightning: [] }
let phase = 'init'
let prevEvent = ''
let firstBottleKm = -1

function fail(msg) { console.error('❌', msg); client.deactivate(); process.exit(1); }

const client = new Client({
  webSocketFactory: () => new SockJS(`${BASE}/ws`),
  reconnectDelay: 5000,
  onConnect: () => {
    client.subscribe(`/topic/player/${clientId}/state`, (m) => {
      const st = JSON.parse(m.body)
      const voy = st.voyage

      if (phase === 'init') {
        client.publish({ destination: '/app/travel', body: JSON.stringify({ clientId, to: TARGET }) })
        phase = 'planned'
        return
      }
      if (phase === 'planned') {
        console.log('-> 启航', TARGET, `全程${Math.round(voy.totalKm)}km`)
        client.publish({ destination: '/app/sail', body: JSON.stringify({ clientId }) })
        phase = 'sailing'
        return
      }
      if (!voy) { report(); return }

      // 事件“边沿”计数：v.event 只在真正触发时变化
      const km = Math.round(voy.traveledKm)
      if (voy.event && voy.event !== prevEvent) {
        if (voy.event.startsWith('🏴')) { hits.pirate.push(km); }
        else if (voy.event.startsWith('⚡')) { hits.lightning.push(km); }
        else if (voy.event.startsWith('💰 捡到')) {
          hits.bottle.push(km)
          if (firstBottleKm < 0) firstBottleKm = km
        } else if (voy.event.startsWith('🌊 海上见了')) { hits.ambient.push(km); }
      }
      prevEvent = voy.event || ''

      if (voy.pendCombat) {
        const ch = ['fight', 'flee', 'pay'][Math.floor(Math.random() * 3)]
        client.publish({ destination: '/app/sailDecision', body: JSON.stringify({ clientId, choice: ch }) })
      } else if (voy.offered) {
        client.publish({ destination: '/app/sailDecision', body: JSON.stringify({ clientId, choice: 'continue' }) })
      } else {
        clicks++
        client.publish({ destination: '/app/sail', body: JSON.stringify({ clientId }) })
      }
    })
    client.publish({ destination: '/app/login', body: JSON.stringify({ clientId, name: '限流验证船长' }) })
  },
  onStompError: (f) => { console.error('STOMP 错误', f.body); process.exit(1) },
})
let clicks = 0

function check(name, arr, max, spacing, label) {
  const sorted = [...arr].sort((a, b) => a - b)
  console.log(`   ${label}: ${sorted.length} 次（上限${max}） @ ${sorted.join(', ') || '-'}km`)
  if (sorted.length > max) fail(`${name} 出现 ${sorted.length} 次，超过上限 ${max}`)
  for (let i = 1; i < sorted.length; i++) {
    const gap = sorted[i] - sorted[i - 1]
    if (gap < spacing) fail(`${name} 两次仅相隔 ${gap}km < ${spacing}km`)
  }
  return sorted
}

function report() {
  console.log(`---- 限流验证结果（共 ${clicks} 击） ----`)
  check('海盗', hits.pirate, 2, 1000, '🏴 海盗')
  check('漂流瓶', hits.bottle, 5, 600, '🍾 漂流瓶')
  check('彩蛋', hits.ambient, 10, 300, '🐬 海上见闻')
  check('雷击', hits.lightning, 1, 150, '⚡ 雷击')
  if (firstBottleKm >= 0 && firstBottleKm < 100) fail(`漂流瓶在 ${firstBottleKm}km 就出现了（应≥100km）`)
  if (hits.lightning.length && hits.lightning[0] < 3000) fail('雷击发生在 3000km 以内，违反限制')
  if (process.env.NO_BOTTLE === '1') {
    console.log(`⚡ 雷击×${hits.lightning.length}（其余事件本轮被清零）${hits.lightning.length ? '，且发生在' + hits.lightning.join('km, ') + 'km' : '——本轮未遇雷雨，未触发（属随机）'}`)
    if (hits.lightning.length > 1) fail('雷击超过上限 1')
  } else if (firstBottleKm < 0) {
    fail('全程没有漂流瓶（把 per_km 调大后重跑）')
  }
  console.log(`✅ 各事件次数与间距均符合限流；首瓶=${firstBottleKm}km（≥100）`)
  client.deactivate()
  process.exit(0)
}

client.activate()
setTimeout(() => fail(`超时（已操作 ${clicks} 击）`), 420000)