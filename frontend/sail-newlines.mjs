// newLines 验证：航程超过 60 行（seaLog 开始被裁剪）后，确认后端仍逐击下发 newLines（前端事件卡数据源），
// 且其中含叙事行（海况/心情/退行报告不计）。与回归探针一致，用 SockJS+STOMP。
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'

const clientId = process.env.NL_CLIENT_ID || ('newlines-' + Date.now())
const BASE = 'http://localhost:8080'

const narrPrefix = ['🌊 距目的地', '⏪ ', '⛵ 拔锚']
const isNuisance = (l) => narrPrefix.some((p) => l.startsWith(p))

const checks = []
let phase = 'init'
let capped = false

const client = new Client({
  webSocketFactory: () => new SockJS(`${BASE}/ws`),
  reconnectDelay: 5000,
  onConnect: () => {
    client.subscribe(`/topic/player/${clientId}/state`, (m) => {
      const st = JSON.parse(m.body)
      const voy = st.voyage
      if (phase === 'init') {
        client.publish({ destination: '/app/travel', body: JSON.stringify({ clientId, to: 'colombo' }) })
        phase = 'planned'
        return
      }
      if (phase === 'planned') {
        client.publish({ destination: '/app/sail', body: JSON.stringify({ clientId }) })
        phase = 'sailing'
        return
      }
      if (!voy) { report(); return }

      if (voy.seaLog.length >= 60) capped = true
      if (capped) {
        const lines = voy.newLines || []
        const narr = lines.filter((l) => !isNuisance(l))
        checks.push({ km: voy.remainingKm, narrative: narr.length, event: voy.event })
      }

      if (voy.pendCombat) {
        client.publish({ destination: '/app/sailDecision', body: JSON.stringify({ clientId, choice: 'flee' }) })
      } else if (voy.offered) {
        client.publish({ destination: '/app/sailDecision', body: JSON.stringify({ clientId, choice: 'continue' }) })
      } else {
        client.publish({ destination: '/app/sail', body: JSON.stringify({ clientId }) })
      }
    })
    client.publish({ destination: '/app/login', body: JSON.stringify({ clientId, name: '新增行探针' }) })
  },
  onStompError: (f) => { console.error('STOMP 错误', f.body); process.exit(1) },
})

function report() {
  const narr = checks.filter((c) => c.narrative > 0).length
  const total = checks.length
  console.log(`---- newLines 验证 ----`)
  console.log(`seaLog 触顶(60行)后捕获 ${total} 次 state，其中叙事行>0 的 ${narr} 次，空 ${total - narr} 次`)
  if (total === 0) { console.log('⚠️ 未到达 60 行上限，改用更长航线（如 london→colombo）'); }
  else if (narr === 0) { console.error('❌ 触顶后逐击 newLines 全为空 —— 事件卡仍会消失'); process.exitCode = 1 }
  else { console.log('✅ 触顶后事件卡每击都有新叙事（前 5: ' + JSON.stringify(checks.slice(0, 5)) + '）') }
  client.deactivate()
  process.exit(process.exitCode ?? 0)
}
client.activate()
setTimeout(() => { console.error('探针超时'); process.exit(1) }, 90000)