// 端到端冒烟测试（含 MySQL 持久化校验）
// 固定 clientId，登录 -> 买葡萄酒5 -> 校验数据库行 -> 卖葡萄酒5 -> 再校验数据库行
// 运行：在 frontend/ 且 npm install 完成后，执行 node smoke-test.mjs（需后端在 8080）
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'

// 可用 SMOKE_CLIENT_ID 环境变量换一个全新 id，用来验证「新玩家 INSERT」路径
const clientId = process.env.SMOKE_CLIENT_ID || 'persist-smoke'
const BASE = 'http://localhost:8080'
let goldBefore = null
let bought = false

async function dbRow() {
  try {
    const r = await fetch(`${BASE}/debug/player/${clientId}`)
    return await r.json()
  } catch (e) {
    return null
  }
}

const client = new Client({
  webSocketFactory: () => new SockJS(`${BASE}/ws`),
  reconnectDelay: 5000,
  onConnect: () => {
    client.subscribe(`/topic/player/${clientId}/state`, async (m) => {
      const st = JSON.parse(m.body)
      console.log('[状态]', `金币=${st.you.gold} 港口=${st.you.port} 舱位=${JSON.stringify(st.you.cargo)}`)
      if (goldBefore === null) {
        goldBefore = st.you.gold
        console.log('-> 买入 葡萄酒 x5')
        client.publish({ destination: '/app/trade', body: JSON.stringify({ clientId, action: 'buy', item: 'wine', count: '5' }) })
      } else if (!bought) {
        bought = true
        const after = st.you.gold
        console.log(`[校验] 买前=${goldBefore} 买后=${after}`)
        const db = await dbRow()
        console.log('[DB-买后]', db ? `gold=${db.gold} cargo=${JSON.stringify(db.cargo)}` : '❌ 无记录(持久化失败)')
        console.log('-> 卖出 葡萄酒 x5')
        client.publish({ destination: '/app/trade', body: JSON.stringify({ clientId, action: 'sell', item: 'wine', count: '5' }) })
      } else {
        const after = st.you.gold
        const db = await dbRow()
        console.log('[DB-卖后]', db ? `gold=${db.gold} cargo=${JSON.stringify(db.cargo)}` : '❌ 无记录')
        const ok = db && db.gold === after
        console.log(ok ? '✅ 买卖闭环 + MySQL 持久化均通过' : '⚠️ 数据库与内存不一致')
        client.deactivate()
        process.exit(ok ? 0 : 1)
      }
    })
    client.subscribe(`/topic/player/${clientId}/msg`, (m) => console.log('[消息]', JSON.parse(m.body).text))
    client.publish({ destination: '/app/login', body: JSON.stringify({ clientId, name: '测试船长' }) })
  },
  onStompError: (f) => { console.error('STOMP 错误', f.body); process.exit(1) }
})

client.activate()
setTimeout(() => { console.error('❌ 超时'); process.exit(1) }, 15000)
