<script setup>
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { game, portName, sail, decide } from '../game'

const router = useRouter()
const seaLog = ref([])
const wasSailing = ref(false)
const destName = ref('')
const arrived = ref(false)
const arrivedMsg = ref('')

watch(
  () => game.voyage,
  (v) => {
    if (v) {
      seaLog.value = v.seaLog
      destName.value = v.destName
      wasSailing.value = true
      arrived.value = false
    } else if (wasSailing.value) {
      arrived.value = true
      arrivedMsg.value = game.toast || `航程结束，抵达了 ${destName.value || '某港'}`
      wasSailing.value = false
    }
  },
  { immediate: true }
)

const remainingClicks = computed(() => {
  const v = game.voyage
  if (!v) return 0
  return Math.max(0, v.clicks - v.clicked)
})

const progress = computed(() => {
  const v = game.voyage
  if (!v) return 0
  return Math.round((v.traveledKm / v.totalKm) * 100)
})

function doSail() {
  if (game.voyage?.offered) return
  sail()
}

function goBack() {
  if (game.voyage?.offered) return
  sail('back')
}

function choose(choice) {
  decide(choice)
}

function goMarket() {
  arrived.value = false
  router.push('/')
}
</script>

<template>
  <div>
    <!-- 未在航行（且无上次到港记录） -->
    <div v-if="!game.voyage && !arrived" class="panel">
      <b>⛵ 航行</b>
      <p class="hint">当前没有进行中的航程。请前往「🗺 航海图」选择一个目标港口。</p>
      <button @click="router.push('/map')">🗺 打开航海图</button>
    </div>

    <!-- 到港结算 -->
    <div v-if="arrived" class="panel">
      <b>🏝 {{ arrivedMsg }}</b>
      <div class="arrived">
        <template v-for="(l, i) in seaLog" :key="i">
          <div class="logline">{{ l }}</div>
        </template>
      </div>
      <button @click="goMarket">🏝 进入港口市场</button>
    </div>

    <!-- 航行中 -->
    <div v-if="game.voyage" class="panel">
      <b>⛵ {{ game.voyage.departed ? '航程' : '航线（未出发）' }}：{{ game.voyage.fromName }} → {{ game.voyage.destName }}</b>
      <p v-if="game.voyage.weather" class="dockweather">🌤 当前港外海况：{{ game.voyage.weather }}</p>
      <div class="stats">
        已航行 <b>{{ game.voyage.traveledKm }}</b> / {{ game.voyage.totalKm }} 公里
        ｜ {{ progress }}%
      </div>
      <div class="bar"><div class="fill" :style="{ width: progress + '%' }"></div></div>

      <p v-if="!game.voyage.departed && game.voyage.weatherWarned" class="storm-warn">
        ⚠️ 现在出海危险（{{ game.voyage.weather }}）——再按一次「出发」是执意启航，或「返回」改期。
      </p>

      <div class="sailrow">
        <div class="side left">
          <button
            v-if="game.voyage.departed"
            class="backbtn"
            :disabled="!!game.voyage.offered"
            @click="goBack"
          >⏪ 后退 10 公里</button>
          <button v-else class="rtnbtn" :disabled="!!game.voyage.offered" @click="choose('return')">↩ 返回（取消航线）</button>
        </div>
        <div class="side right">
          <button
            class="sailbtn"
            :class="{ warn: game.voyage.weatherWarned }"
            :disabled="!!game.voyage.offered"
            @click="doSail"
          >{{ game.voyage.departed ? '⛵ 前进 10 公里' : (game.voyage.weatherWarned ? '⛈ 仍要启航！' : '⛵ 出发！前进 10 公里') }}</button>
        </div>
      </div>

      <!-- 途经地：两个菜单 -->
      <div v-if="game.voyage.offered" class="offer">
        <p class="offertext">前方正在经过 <b>{{ game.voyage.offered.name }}</b>，请选择：</p>
        <button class="enter" @click="choose('enter')">🏝 进到{{ game.voyage.offered.name }}地</button>
        <button class="on" @click="choose('continue')">🚢 继续航行</button>
      </div>

      <div class="sealog">
        <div v-for="(l, i) in seaLog" :key="i" class="logline">{{ l }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
  .hint { color:#8899aa; font-size:13px; }
  .dockweather { margin:0 0 2px; font-size:13px; color:#8fb3d9; }
  .storm-warn { color:#ffd479; font-size:13px; border:1px solid #ffd479; border-radius:6px; padding:6px 8px; background:#241c10; margin:4px 0 2px; }
  .sailbtn.warn { background:#5a2c1c; }
  .stats { margin:8px 0; font-size:14px; }
  .bar { height:8px; background:#1c2742; border-radius:4px; overflow:hidden; margin:6px 0 10px; }
  .fill { height:100%; background:#34507f; transition:width .3s; }
  .sailrow { display:flex; justify-content:space-between; align-items:center; margin:10px 0; }
  .sailrow .side { display:flex; }
  .sailbtn { padding:12px 24px; font-size:16px; font-weight:bold; background:#1c3a5a; }
  .backbtn { padding:12px 18px; font-size:15px; background:#3a2a1c; }
  .rtnbtn { padding:12px 18px; font-size:15px; background:#241c18; }
  button:disabled { opacity:.5; }
  .offer { border:1px solid #ffd479; border-radius:8px; padding:10px; margin:10px 0; background:#1a1f2e; }
  .offertext { margin:0 0 8px; color:#ffd479; }
  .offer button { font-size:15px; padding:8px 16px; }
  .enter { background:#1c2b4a; }
  .on { background:#153017; }
  .sealog { max-height:300px; overflow:auto; font-size:13px; line-height:1.7; border-top:1px dashed #2a3a5a; margin-top:10px; padding-top:8px; }
  .logline { padding:1px 0; }
  .arrived { max-height:300px; overflow:auto; font-size:13px; line-height:1.7; margin:8px 0; }
</style>