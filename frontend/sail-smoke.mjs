// 航行闭环冒烟测试（手动航行 v2：可往返、可返回、随机天气事件）
// 流程：登录 -> 规划航线(未出发) -> 返回取消 -> 再规划 -> 出发 x5 -> 后退 x1 校验里程回退
//      -> 继续前进直到到港，全程收集遭遇事件（海盗/漂流瓶/雷击 + 内心OS）
// 运行：node sail-smoke.mjs（需后端在 8080）；可用 SAIL_CLIENT_ID 换全新玩家。
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'

const clientId = process.env.SAIL_CLIENT_ID || 'sail-smoke'
const TARGET = process.env.SAIL_TARGET || 'copenhagen'
const BASE = 'http://localhost:8080'

let phase = 'init'          // init -> planned -> cancelled -> departing -> sailing
let clicks = 0
let goldBefore = null
let startPort = null
let kmBeforeBack = null     // 后退前的里程
let backDone = false
let seaLogPrev = new Set()   // 已见过的 seaLog 行（seaLog 会 trim，用“首次出现”判定新增行）

function fail(msg) {
  console.error('❌', msg)
  client.deactivate()
  process.exit(1)
}

const client = new Client({
  webSocketFactory: () => new SockJS(`${BASE}/ws`),
  reconnectDelay: 5000,
  onConnect: () => {
    client.subscribe(`/topic/player/${clientId}/msg`, (m) => {
      const t = JSON.parse(m.body).text
      console.log('[消息]', t)
      if (phase === 'cancelled' && !t.includes('取消航线')) fail('返回消息不符合预期: ' + t)
    })

    client.subscribe(`/topic/player/${clientId}/state`, (m) => {
      const st = JSON.parse(m.body)
      const you = st.you
      const voy = st.voyage

      // ---- 到港判定｜在任一行驶阶段，voyage 消失且停在目标港 = 成功 ----
      if (phase === 'sailing' && !voy) {
        if (you.port === TARGET) {
          console.log(`✅ 航行闭环通过：共点击 ${clicks} 次抵达 ${you.port}；金币变化 ${you.gold - goldBefore}（漂流瓶/海盗事件合计）`)
          client.deactivate()
          process.exit(0)
        }
        fail(`航程结束但不在目标港 port=${you.port}`)
        return
      }

      if (phase === 'init') {
        goldBefore = you.gold
        startPort = you.port
        console.log('[状态]', `金币=${you.gold} 港口=${you.port}`)
        console.log('-> 规划航线前往', TARGET, '（未出发）')
        client.publish({ destination: '/app/travel', body: JSON.stringify({ clientId, to: TARGET }) })
        phase = 'planned'
        return
      }

      if (phase === 'planned') {
        if (!voy) return fail('规划后应有航线')
        if (voy.departed) return fail('规划阶段 departed 应为 false')
        console.log('   [未出发] 航线：', `${voy.fromName} -> ${voy.destName}`, `全程${voy.totalKm}km 约${voy.clicks}次`)
        console.log('-> 返回（取消航线）')
        client.publish({ destination: '/app/sailDecision', body: JSON.stringify({ clientId, choice: 'return' }) })
        phase = 'cancelled'
        return
      }

      if (phase === 'cancelled') {
        if (voy) return fail('返回后航线应取消')
        console.log('   [返回] 港口 =', you.port, '（应在出发港', startPort, '）')
        if (you.port !== startPort) return fail('取消航线后应留在出发港')
        console.log('-> 再次规划航线')
        client.publish({ destination: '/app/travel', body: JSON.stringify({ clientId, to: TARGET }) })
        phase = 'departing'
        return
      }

      if (phase === 'departing') {
        console.log('-> 出发 x1（前进10km）')
        client.publish({ destination: '/app/sail', body: JSON.stringify({ clientId }) })
        phase = 'sailing'
        return
      }

      // phase === 'sailing' 且仍在航行：逐次前进 / 后退一次
      clicks++
      // 打印本轮新增的事件叙事（海盗/漂流瓶/雷击 均带【】标题，后随内心OS）
      const cur = voy.seaLog || []
      const added = []
      for (const ln of cur) {
        if (!seaLogPrev.has(ln)) {
          seaLogPrev.add(ln)
          added.push(ln)
        }
      }
      for (const ln of added) {
        if (ln.includes('【')) console.log(`   ✨ [${clicks}] ${ln}`)
      }
      // 海盗对峙：随机拿个主意，别卡死
      if (voy.pendCombat) {
        const ch = ['fight', 'flee', 'pay'][Math.floor(Math.random() * 3)]
        console.log(`   [${clicks}] 海盗对峙 -> ${ch}`)
        client.publish({ destination: '/app/sailDecision', body: JSON.stringify({ clientId, choice: ch }) })
      } else if (voy.offered) {
        console.log(`   [${clicks}] 途经地 ${voy.offered.name} -> 继续航行`)
        client.publish({ destination: '/app/sailDecision', body: JSON.stringify({ clientId, choice: 'continue' }) })
      } else if (!backDone && voy.departed && Number(voy.traveledKm) >= 200) {
        kmBeforeBack = Number(voy.traveledKm)
        console.log(`   [${clicks}] 里程=${voy.traveledKm}km，后退 x1`)
        client.publish({ destination: '/app/sail', body: JSON.stringify({ clientId, dir: 'back' }) })
        backDone = true
      } else if (backDone && kmBeforeBack !== null) {
        console.log(`   [${clicks}] 后退后里程=${voy.traveledKm}km（后退前 ${kmBeforeBack}）`)
        if (Number(voy.traveledKm) >= kmBeforeBack) fail('后退未能减少里程')
        kmBeforeBack = null
        console.log('-> 继续前进')
        client.publish({ destination: '/app/sail', body: JSON.stringify({ clientId }) })
      } else {
        if (clicks % 5 === 0) console.log(`   [${clicks}] ${voy.traveledKm}/${voy.totalKm}km`)
        client.publish({ destination: '/app/sail', body: JSON.stringify({ clientId }) })
      }
    })

    client.publish({ destination: '/app/login', body: JSON.stringify({ clientId, name: '航行测试船长' }) })
  },
  onStompError: (f) => { console.error('STOMP 错误', f.body); process.exit(1) },
})

client.activate()
setTimeout(() => fail(`超时（已操作 ${clicks} 次）`), 360000)