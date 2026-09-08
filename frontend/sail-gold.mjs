// 金币一致性验证：
//  1) 每次状态刷新，新出现事件行推算的净金币变化必须等于金币实际变化。
//  2) 航行中途重新 login（刷新/开第二标签页的模拟）不得回退进行中的金币与航程。
// 运行：node sail-gold.mjs（需后端 8080）；用 GOLD_CLIENT_ID=... 换全新玩家。
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'

const clientId = process.env.GOLD_CLIENT_ID || 'gold-check'
const TARGET = 'copenhagen'
const BASE = 'http://localhost:8080'
const RELOGIN_AT = 30

let phase = 'init'
let clicks = 0
let goldPrev = null
let seen = new Set()
let relogined = false

function fail(msg) {
  console.error('❌', msg)
  client.deactivate()
  process.exit(1)
}

function newBankLines(voy) {
  // 只统计“本轮新出现”的 ✨ 行（seaLog 会保留历史，必须按首次出现去重）
  const out = []
  for (const l of voy.seaLog || []) {
    if (!seen.has(l)) {
      seen.add(l)
      if (l.includes('✨')) out.push(l)
    }
  }
  return out
}

// 从事件行推算本击金币净变化（支持：金币+/朗姆/+被抢走/沉船宝藏）
function netFrom(lines) {
  let net = 0
  for (const l of lines) {
    if (l.includes('免费入舱')) continue // 白捡货物不涉及金币
    const up = l.match(/✨\s*金币 \+(\d+)/)
    const rum = l.match(/…和 (\d+) 枚金币/)
    const stolen = l.match(/💰 被抢走 (\d+) 金币/)
    if (up) net += Number(up[1])
    else if (rum) net += Number(rum[1])
    else if (stolen) net -= Number(stolen[1])
  }
  return net
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
        goldPrev = you.gold
        console.log('[状态] 初始金币 =', you.gold)
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
        console.log(`✅ 航程结束：${clicks} 击，金币 ${goldPrev} -> ${you.gold}（变化 ${you.gold - goldPrev}）${relogined ? '，中途重登后金币/航程未被回退' : ''}`)
        client.deactivate()
        process.exit(0)
      }

      clicks++
      const bank = newBankLines(voy)
      const expected = netFrom(bank)
      const actual = you.gold - goldPrev
      if (expected !== 0 || actual !== 0) {
        console.log(`  [${clicks}] 事件行推断 ${expected >= 0 ? '+' : ''}${expected}，实际 ${actual >= 0 ? '+' : ''}${actual}`)
      }
      if (expected !== actual) {
        console.error('   ▸ 不一致！新事件行:\n     ' + bank.join('\n     '))
        fail('金币增量与事件描述不符')
      }
      goldPrev = you.gold

      if (!relogined && clicks === RELOGIN_AT) {
        relogined = true
        console.log(`  [re] 航行中重登（模拟刷新页面/开第二个标签页）…`)
        client.publish({ destination: '/app/login', body: JSON.stringify({ clientId, name: '金币验证船长' }) })
        return // 下一状态紧接着到来，继续正常校验
      }

      if (voy.offered) {
        client.publish({ destination: '/app/sailDecision', body: JSON.stringify({ clientId, choice: 'continue' }) })
      } else {
        client.publish({ destination: '/app/sail', body: JSON.stringify({ clientId }) })
      }
    })
    client.publish({ destination: '/app/login', body: JSON.stringify({ clientId, name: '金币验证船长' }) })
  },
  onStompError: (f) => { console.error('STOMP 错误', f.body); process.exit(1) },
})

client.activate()
setTimeout(() => fail(`超时（已 ${clicks} 击）`), 420000)