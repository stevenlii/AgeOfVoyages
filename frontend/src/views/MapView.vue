<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { game, portName, startVoyage } from '../game'

const router = useRouter()
const current = ref(null)

const here = computed(() => game.you?.port)

function select(regionId) {
  current.value = current.value === regionId ? null : regionId
}

const portsOf = computed(() => {
  if (!current.value) return []
  return game.ports
    .filter((p) => p.region === current.value)
    .sort((a, b) => a.distanceKm - b.distanceKm)
})

function choose(p) {
  if (game.voyage) { router.push('/sail'); return }
  if (p.id === here.value) return
  startVoyage(p.id)
  router.push('/sail')
}
</script>

<template>
  <div>
    <div class="panel">
      <b>🗺 航海图</b>
      <div class="meta" v-if="game.you">
        当前：<b>{{ portName(here) }}</b> 港
        <span v-if="game.voyage"> ｜ ⛵ 正在航行中，请到「继续航行」完成航程</span>
      </div>

      <!-- 第一级：海域 -->
      <div class="regions">
        <button
          v-for="r in game.regions"
          :key="r.id"
          :class="{ active: current === r.id }"
          @click="select(r.id)"
        >{{ r.name }}</button>
      </div>

      <p v-if="!current" class="hint">↑ 先选择一个海域，再选择要前往的港口（点击名称即为超链接）。</p>

      <!-- 第二级：该海域的港口 -->
      <div v-if="portsOf.length" class="ports">
        <template v-for="p in portsOf" :key="p.id">
          <a
            class="port"
            :class="{ disabled: p.id === here || game.voyage }"
            href="#"
            @click.prevent="choose(p)"
            :title="p.id === here ? '您已在该港' : (game.voyage ? '航行中不可更改目标' : '点击启航前往')"
          >
            <span class="pname">{{ p.name }}</span>
            <span class="pdist">
              {{ p.id === here ? '📍 当前' : `距此约 ${p.distanceKm} 公里 · 约 ${Math.ceil(p.distanceKm / 10)} 次点击` }}
            </span>
          </a>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
  .meta { margin:8px 0; font-size:14px; }
  .regions { display:flex; flex-wrap:wrap; gap:8px; margin:10px 0; }
  .regions button { padding:8px 14px; font-size:14px; }
  .regions button.active { background:#27406e; border-color:#9fc0ff; }
  .hint { color:#8899aa; font-size:13px; margin:6px 0; }
  .ports { display:flex; flex-direction:column; margin-top:8px; }
  .port {
    display:flex; justify-content:space-between; align-items:center;
    padding:8px 10px; margin:2px 0; border:1px solid #2a3a5a; border-radius:6px;
    color:#cfe0ff; text-decoration:none; background:#0c1428;
  }
  .port:hover { background:#1c2b4a; }
  .port.disabled { opacity:.5; pointer-events:none; }
  .pname { font-weight:bold; }
  .pdist { color:#8899aa; font-size:12px; }
</style>