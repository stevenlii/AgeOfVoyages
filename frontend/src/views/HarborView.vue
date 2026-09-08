<script setup>
import { ref } from 'vue'
import { game, portName, goodName, trade } from '../game'

const qty = ref({})

function trendOf(id) { return game.market?.[id]?.trend ?? 0 }
function trendText(t) { return t ? (t > 0 ? '↑' : '↓') + Math.abs(t) + '%' : '—' }
function trendClass(t) { return t >= 8 ? 'up' : t <= -8 ? 'down' : 'flat' }

function doTrade(action, item) {
  const n = qty.value[item] || 1
  trade(action, item, Math.max(1, parseInt(n) || 1))
}

function cargoUsed() {
  const c = game.you?.cargo || {}
  return Object.values(c).reduce((a, b) => a + b, 0)
}
</script>

<template>
  <div>
    <div class="panel">
      <b>🏝 港口市场 · {{ portName(game.you?.port) }}</b>
      <table v-if="game.you">
        <thead>
          <tr><th>货物</th><th>买入价</th><th>卖出价</th><th>相比平常</th><th>数量</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="g in game.goods" :key="g.id">
            <td>{{ g.name }}</td>
            <td>{{ game.market[g.id]?.buy }}</td>
            <td>{{ game.market[g.id]?.sell }}</td>
            <td :class="trendClass(trendOf(g.id))">{{ trendText(trendOf(g.id)) }}</td>
            <td><input v-model="qty[g.id]" type="number" min="1" value="1" /></td>
            <td>
              <button @click="doTrade('buy', g.id)">买</button>
              <button @click="doTrade('sell', g.id)">卖</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="game.you" class="cargo">
        📦 已装船：
        <span v-for="g in game.goods" :key="g.id">
          <template v-if="game.you.cargo[g.id]">{{ goodName(g.id) }}×{{ game.you.cargo[g.id] }} &nbsp;</template>
        </span>
        <span v-if="!cargoUsed()">（空舱）</span>
      </div>
    </div>

    <div class="panel hint">
      💡 想要出港远航？右上角进入「🗺 航海图」，先选海域，再点选目标港即可启航。
      航行途中每点击一次前进 10 公里，注意沿途的天气、海面、海盗与雷雨。
    </div>
  </div>
</template>

<style scoped>
  .cargo { margin-top:8px; font-size:13px; color:#cfd8ea; }
  .hint { color:#8899aa; font-size:13px; }
  input[type=number] { width:60px; }
</style>