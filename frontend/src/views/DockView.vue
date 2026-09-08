<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { game, portName, goodName } from '../game'

const router = useRouter()

const port = computed(() => portName(game.you?.port))
const gold = computed(() => game.you?.gold ?? 0)
const cargo = computed(() => game.you?.cargo ?? {})
const cargoUsed = computed(() =>
  Object.values(cargo.value).reduce((a, b) => a + b, 0)
)

function cargoItems() {
  return game.goods
    .filter((g) => cargo.value[g.id])
    .map((g) => `${goodName(g.id)}×${cargo.value[g.id]}`)
}
</script>

<template>
  <div>
    <div class="panel">
      <b>⚓ {{ port }}码头</b>
      <p class="hint">船刚靠上 {{ port }} 的码头。这里是登岸的门户——市场、居民区都从这里分头去。</p>

      <div class="stat">
        💰 金币：<b>{{ gold }}</b> ｜ 📦 舱位：<b>{{ cargoUsed }}/{{ game.you?.cargoCap }}</b>
      </div>
      <div v-if="cargoUsed" class="cargo">📦 已装船：{{ cargoItems().join('　') }}</div>
      <div v-else class="cargo hint">（空舱）</div>
    </div>

    <div class="panel places">
      <b>🗺 前往何处</b>
      <div class="grid">
        <button class="place market" @click="router.push('/')">🏝 市场<small>买卖货物</small></button>
        <button class="place sail" @click="router.push('/map')">🗺 航海图<small>规划下一段航程</small></button>
        <button class="place town" @click="router.push('/town')">🏘 居民区<small>街坊与布告</small></button>
      </div>
    </div>
  </div>
</template>

<style scoped>
  .hint { color:#8899aa; font-size:13px; }
  .stat { margin:10px 0; font-size:14px; }
  .stat b { color:#9fe6c0; }
  .cargo { margin-top:6px; font-size:13px; color:#cfd8ea; }
  .places { margin-top:10px; }
  .grid { display:flex; gap:10px; flex-wrap:wrap; margin-top:10px; }
  .place {
    flex:1; min-width:140px; display:flex; flex-direction:column; align-items:flex-start;
    padding:14px; font-size:16px; font-weight:bold; border-radius:10px; text-align:left;
  }
  .place small { font-weight:normal; font-size:12px; color:#9fb3cf; margin-top:4px; }
  .place.market { background:#1c2b4a; }
  .place.sail { background:#15303a; }
  .place.town { background:#2a2440; }
</style>
