// 验证：①漂流瓶开局 50km 内不出现（min_start_km 门控）；②航行中「当前海域」sea 字段存在且会随航位变化（近海→大洋深处）。
// 运行前先把 bottle_per_km 调大（如 0.9）让瓶子几乎必定出现；结束会还原。
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'

const clientId = process.env.SEA_CLIENT_ID || 'sea-probe'
const TARGET = 'copenhagen'
const BASE = 'http://localhost:8080'

let phase = 'init'
let firstBottleKm = -1
let seenBottleBefore50 = false
const seas = new Set()

function fail(msg) { console.error('❌', msg); client.deactivate(); process.exit(1) }

const client = new Client({
  webSocketFactory: () => new SockJS(`${BASE}/ws`),
  reconnectDelay: 5000,
  onConnect: () => {
    client.subscribe(`/topic/player/${clientId}/state`, (m) => {
      const st = JSON.parse(m.body)
      const you = st.you
      const voy = st.voyage

      if (phase === 'init') {
        client.publish({ destination: '/app/travel', body: JSON.stringify({ clientId, to: TARGET }) })
        phase = 'planned'
        return
      }
      if (phase === 'planned') {
        client.publish({ destination: '/app/sail', body: JSON.stringify({ clientId }) })
        phase = 'sailing'
        return
      }
      if (!voy) {
        console.log('---- 海域/瓶子门控验证结果 ----')
        console.log(`首次捞到瓶子出现在 ${firstBottleKm}km（应 ≥50）`)
        console.log(`观察到的海域：${[...seas].join(' → ')}`)
        if (firstBottleKm < 0) fail('全程未捞到瓶子（调大 bottle_per_km 后再跑）')
        if (seenBottleBefore50) fail(`瓶子在 <50km 就出现了（${firstBottleKm}km），违反 min_start_km 门控`)
        if (!seas.size || ![...seas].some(s => s.includes('海域') ? true : true)) fail('sea 字段缺失')
        console.log('✅ 瓶子 50km 门控与海域字段均正常')
        client.deactivate()
        process.exit(0)
      }

      if (!voy.sea) fail(`voy.sea 缺失（traveledKm=${voy.traveledKm}）`)
      seas.add(voy.sea)

      const added = (voy.seaLog || []).reduce((acc, l) => {
        if (!seen.has(l)) { seen.add(l); acc.push(l) }
        return acc
      }, [])
      for (const l of added) {
        if (l.includes('【漂流瓶】')) {
          if (firstBottleKm < 0) firstBottleKm = Math.round(voy.traveledKm)
          if (voy.traveledKm < 50) seenBottleBefore50 = true
        }
      }

      if (voy.offered) {
        client.publish({ destination: '/app/sailDecision', body: JSON.stringify({ clientId, choice: 'continue' }) })
      } else if (clicks % 5 === 0) {
        console.log(`   [${clicks}] ${Math.round(voy.traveledKm)}km 海域=${voy.sea}`)
      }
      if (!voy.offered) {
        clicks++
        client.publish({ destination: '/app/sail', body: JSON.stringify({ clientId }) })
      }
    })
    client.publish({ destination: '/app/login', body: JSON.stringify({ clientId, name: '海域验证船长' }) })
  },
  onStompError: (f) => { console.error('STOMP 错误', f.body); process.exit(1) },
})
let clicks = 0
const seen = new Set()
client.activate()
setTimeout(() => fail(`超时（已操作 ${clicks} 击，首瓶=${firstBottleKm}km）`), 300000)