<script setup>
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { game, portName, sail, decide, forecast } from '../game'

const router = useRouter()
const seaLog = ref([])
const wasSailing = ref(false)
const destName = ref('')
const arrived = ref(false)
const arrivedMsg = ref('')
const showLog = ref(false)
const showForecast = ref(false)
// 「当前事件」卡：界面上只展示「本轮新增」的叙事行（事件来了才显示、开船后自然消失）。
// 新增行由后端直接下发（voyage.newLines，每次动作增量），不依赖 seaLog 长度，seaLog 裁剪到 60 行不影响
const turnLines = ref([])

// 天气对应的简短提示（与后端 WEATHER_HELP 保持一致，仅用于界面辅助说明）
const WEATHER_HELP = {
  '晴空万里': '风和日丽，宜扬帆远航',
  '多云': '云影舒卷，海面平稳',
  '阴雨': '细雨绵绵，注意保暖',
  '狂风大作': '狂风呼啸，舵手须谨慎',
  '下雷雨': '电闪雷鸣，谨防落雷',
}

watch(
  () => game.voyage,
  (v) => {
    if (v) {
      seaLog.value = v.seaLog
      destName.value = v.destName
      wasSailing.value = true
      arrived.value = false
      showLog.value = false
      showForecast.value = false
      turnLines.value = (v.newLines || []).filter(isNarrativeLine)
    } else {
      turnLines.value = []
      if (wasSailing.value) {
        arrived.value = true
        arrivedMsg.value = game.toast || `航程结束，抵达了 ${destName.value || '某港'}`
        wasSailing.value = false
      }
    }
  },
  { immediate: true }
)

const v = computed(() => game.voyage)
const remainingKm = computed(() => (v.value ? v.value.remainingKm : 0))
const weather = computed(() => (v.value ? v.value.weather : ''))
const weatherHelp = computed(() => WEATHER_HELP[weather.value] || '')
const seaHint = computed(() => {
  const s = v.value?.sea || ''
  return s.includes('大洋深处') ? '海盗与雷击只在大洋深处出没' : '近海风平浪静，少有海盗出没'
})
const arrivedPort = computed(() => portName(game.you?.port))

// 海盗对峙中：必须先做出「迎战/甩开/花钱消灾」的选择，才能继续航行
const pendCombat = computed(() => !!(v.value && v.value.pendCombat))

// 叙事行判定：把「距目的地/后退 X 公里」这类机械进度行挡在事件卡外，其余（天气心情、彩蛋、事件、奖励、内心OS）都算叙事
function isNarrativeLine(line) {
  if (!line) return false
  if (line.startsWith('🌊 距目的地还有')) return false
  if (line.startsWith('⏪ 后退')) return false
  return true
}

/** 智能事件标题：大事才有醒目标题（海盗/雷击/漂流瓶大奖）；只是彩蛋或风浪时直接显示内容、不配标题 */
const eventTitle = computed(() => {
  const voy = v.value
  if (!voy) return ''
  if (voy.offered) return `🏝 前方正在经过 ${voy.offered.name}`
  if (pendCombat.value) return '🏴 海盗来了！！！'
  const all = turnLines.value.join('\n')
  if (all.includes('海盗')) return '🏴 海盗来了！！！'
  if (all.includes('⚡【雷击】')) return '⚡ 落雷劈中了船！'
  if (all.includes('沉船宝藏')) return '🗺 挖到沉船宝藏！'
  if (all.includes('✨ 白捡')) return '📦 白捡一箱好货！'
  if (all.includes('✨ 金币 +')) return '💰 海神爷送钱来！'
  if (all.includes('🍾【漂流瓶】')) return '🍾 半瓶朗姆的快乐'
  if (all.includes('💌【漂流瓶】')) return '💌 一封漂洋过海的信'
  return ''
})

/** 给事件卡的每一行上色：
 *  - 内心OS → 斜体淡色
 *  - 收益行（+ 金币 / 白捡）→ 绿色高亮
 *  - 损失行（被抢走 / 损失）→ 红色高亮
 *  - 其余（事件叙事）→ 正文 */
function eventLineClass(line) {
  if (!line) return 'event-narrative'
  if (line.startsWith('（内心OS')) return 'event-os'
  if (line.startsWith('⚔️ 怎么办')) return 'event-choice'
  if (line.includes('金币 +') || line.includes('白捡')) return 'event-gain'
  if (line.includes('被抢走') || line.includes('损失') || line.includes('交出')) return 'event-loss'
  return 'event-narrative'
}

function doSail() {
  if (game.voyage?.offered || pendCombat.value) return
  sail()
}

function goBack() {
  if (game.voyage?.offered || pendCombat.value) return
  sail('back')
}

function choose(choice) {
  decide(choice)
}

function doForecast() {
  showForecast.value = true
  forecast()
}

function goDock() {
  arrived.value = false
  router.push('/dock')
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

    <!-- 到港结算：进入码头（码头与市场是两个独立界面） -->
    <div v-if="arrived" class="panel">
      <b>🏝 {{ arrivedMsg }}</b>
      <div class="arrived">
        <template v-for="(l, i) in seaLog" :key="i">
          <div class="logline">{{ l }}</div>
        </template>
      </div>
      <button class="dockbtn" @click="goDock">⚓ 进入{{ arrivedPort }}码头</button>
    </div>

    <!-- 航行中 -->
    <div v-if="game.voyage" class="panel">
      <b>⛵ {{ game.voyage.departed ? '航程' : '航线（未出发）' }}：{{ game.voyage.fromName }} → {{ game.voyage.destName }}</b>

      <div class="hud">
        <div class="remain">📍 距目的地还有 <b>{{ remainingKm }}</b> 公里</div>
        <div class="sea">🌊 当前海域：<b>{{ game.voyage.sea }}</b> <span class="help">{{ seaHint }}</span></div>
        <div class="weather">🌤 当前海况：<b>{{ weather }}</b> <span class="help">{{ weatherHelp }}</span></div>
      </div>

      <!-- 当前事件：展示「上一次航行动作」叙事的完整段落；前方有可进港地方时，它就是那个途经地事件 -->
      <div v-if="game.voyage.offered || pendCombat || turnLines.length" class="event-card">
        <template v-if="game.voyage.offered && !pendCombat">
          <div class="event-title">🏝 前方正在经过 {{ game.voyage.offered.name }}</div>
          <div class="event-line event-offer">港口的炊烟和叫卖声已经飘进海风里了——要不要顺路进港歇个脚、补点货？</div>
        </template>
        <template v-else>
          <div v-if="eventTitle" class="event-title">{{ eventTitle }}</div>
          <div
            v-for="(line, i) in turnLines"
            :key="i"
            class="event-line"
            :class="eventLineClass(line)"
          >{{ line }}</div>
        </template>
      </div>

      <!-- 海盗对峙：三选一，解决前无法继续航行 -->
      <div v-if="pendCombat" class="combat">
        <div class="combat-title">🏴 海盗拦住了去路，拿个主意！</div>
        <div class="combat-btns">
          <button class="fight" @click="choose('fight')">⚔️ 迎战<div class="sub">五成胜算：赢了抢他一笔，输了被搬货</div></button>
          <button class="flee" @click="choose('flee')">🏃 甩开<div class="sub">保住货物，但会被追回 80~150 公里</div></button>
          <button class="pay" :disabled="(game.you?.gold ?? 0) < 5" @click="choose('pay')">
            💰 花钱消灾<div class="sub">交 5 金币买平安（现有 {{ game.you?.gold ?? 0 }}）</div>
          </button>
        </div>
      </div>

      <p v-if="!game.voyage.departed && game.voyage.weatherWarned" class="storm-warn">
        ⚠️ 港外海况（{{ game.voyage.weather }}）恶劣，再次点击「出发」将执意开船；或点「返回」留在港里。
      </p>

      <!-- 航行动作行：平时是「后退/前进」；前方有可进港地方时换成「继续/进港」；海盗对峙时退场交给上面的三选一面板 -->
      <div v-if="!pendCombat" class="sailrow">
        <template v-if="game.voyage.offered">
          <div class="side left">
            <button class="on" @click="choose('continue')">🚢 继续航行</button>
          </div>
          <div class="side right">
            <button class="enter" @click="choose('enter')">🏝 进到{{ game.voyage.offered.name }}地</button>
          </div>
        </template>
        <template v-else>
          <div class="side left">
            <button
              v-if="game.voyage.departed"
              class="backbtn"
              @click="goBack"
            >⏪ 后退 </button>
            <button v-else class="rtnbtn" @click="choose('return')">↩ 返回（取消航线）</button>
          </div>
          <div class="side right">
            <button
              class="sailbtn"
              :class="{ warn: game.voyage.weatherWarned }"
              @click="doSail"
            >{{ game.voyage.departed ? '⛵ 前进 ' : (game.voyage.weatherWarned ? '⛈ 仍要启航！' : '⛵ 出发！前进') }}</button>
          </div>
        </template>
      </div>

      <!-- 底部：航海日志 / 天气占卜（完整日志请点「航海日志」弹窗查看） -->
      <div class="btnrow">
        <button class="logbtn" @click="showLog = true">📜 航海日志</button>
        <button class="fortbtn" @click="doForecast">🔮 天气占卜（未来3天）</button>
      </div>
    </div>

    <!-- 航海日志弹窗 -->
    <div v-if="showLog" class="modal" @click.self="showLog = false">
      <div class="modalbox">
        <b>📜 航海日志</b>
        <div class="logscroll">
          <div v-for="(l, i) in seaLog" :key="i" class="logline">{{ l }}</div>
        </div>
        <button @click="showLog = false">关闭</button>
      </div>
    </div>

    <!-- 天气占卜弹窗 -->
    <div v-if="showForecast" class="modal" @click.self="showForecast = false">
      <div class="modalbox">
        <b>🔮 天气占卜</b>
        <p class="hint">当前（{{ game.forecastFrom }}）→ 未来三天的海况预报：</p>
        <div v-if="game.forecast.length" class="fdays">
          <div v-for="(d, i) in game.forecast" :key="i" class="fday">
            <div class="fname">{{ d.day }}</div>
            <div class="fweather">🌤 {{ d.weather }}</div>
            <div class="fhelp">{{ d.help }}</div>
          </div>
        </div>
        <p v-else class="hint">正在请海神爷掐指一算……（若迟迟不出，请再点一次）</p>
        <button @click="showForecast = false">关闭</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
  .hint { color:#8899aa; font-size:13px; }
  .help { color:#7f93ad; font-size:12px; margin-left:4px; }
  .storm-warn { color:#ffd479; font-size:13px; border:1px solid #ffd479; border-radius:6px; padding:6px 8px; background:#241c10; margin:4px 0 2px; }
  .sailbtn.warn { background:#5a2c1c; }

  .hud { margin:10px 0; font-size:14px; line-height:1.9; background:#0c1428; border:1px solid #223052; border-radius:8px; padding:10px 12px; }
  .hud .remain b { color:#9fe6c0; font-size:16px; }
  .hud .weather b { color:#9fc0ff; }
  .hud .sea b { color:#7fbf7f; }

  /* 最新事件卡：替代原来的「当前事件 + 心情OS」两行 */
  .event-card { margin:10px 0; background:#0c1428; border:1px solid #223052; border-radius:8px; padding:10px 12px; }
  .event-title { color:#9fc0ff; font-size:14px; font-weight:bold; margin-bottom:6px; }
  .event-line { font-size:13px; line-height:1.7; padding:1px 0; white-space:pre-wrap; }
  .event-offer { color:#ffd479; line-height:1.8; }
  .event-narrative { color:#e7eefc; }
  .event-gain { color:#9fe6c0; font-weight:bold; }
  .event-loss { color:#ff8a8a; font-weight:bold; }
  .event-os { color:#9d8cd9; font-style:italic; }
  .event-choice { color:#ffd479; font-weight:bold; }

  /* 海盗对峙面板 */
  .combat { margin:10px 0; border:1px solid #b8443c; border-radius:8px; padding:10px 12px; background:#2a1214; }
  .combat-title { color:#ff9a8a; font-size:15px; font-weight:bold; margin-bottom:8px; }
  .combat-btns { display:flex; gap:8px; flex-wrap:wrap; }
  .combat-btns button { flex:1 1 150px; padding:10px 8px; font-size:14px; font-weight:bold; line-height:1.4; }
  .combat-btns .sub { display:block; font-weight:normal; font-size:11px; opacity:.8; margin-top:3px; }
  .fight { background:#5a1f1f; }
  .flee { background:#1f3a5a; }
  .pay { background:#3a331a; }

  .btnrow { display:flex; gap:8px; margin:10px 0; }
  .btnrow button { flex:1; padding:10px 12px; font-size:14px; }
  .logbtn { background:#1c2b4a; }
  .fortbtn { background:#2a1c4a; }

  .sailrow { display:flex; justify-content:space-between; align-items:center; margin:10px 0; }
  .sailrow .side { display:flex; }
  .sailbtn { padding:12px 24px; font-size:16px; font-weight:bold; background:#1c3a5a; }
  .backbtn { padding:12px 18px; font-size:15px; background:#3a2a1c; }
  .rtnbtn { padding:12px 18px; font-size:15px; background:#241c18; }
  .sailrow .enter { background:#1c2b4a; font-size:15px; padding:12px 18px; }
  .sailrow .on { background:#153017; font-size:15px; padding:12px 18px; }
  button:disabled { opacity:.5; }
  .arrived { max-height:300px; overflow:auto; font-size:13px; line-height:1.7; margin:8px 0; }
  .logline { padding:1px 0; white-space:pre-wrap; }
  .dockbtn { padding:12px 20px; font-size:15px; font-weight:bold; background:#1c3a5a; }

  .modal { position:fixed; inset:0; background:rgba(0,0,0,.55); display:flex; align-items:center; justify-content:center; z-index:50; }
  .modalbox { width:min(520px, 92vw); max-height:80vh; overflow:auto; background:#0f1730; border:1px solid #34507f; border-radius:10px; padding:16px; }
  .modalbox b { color:#9fc0ff; font-size:16px; }
  .logscroll { max-height:46vh; overflow:auto; font-size:13px; line-height:1.7; margin:10px 0; border-top:1px dashed #2a3a5a; padding-top:8px; }
  .fdays { display:flex; gap:8px; margin:10px 0; }
  .fday { flex:1; background:#0c1428; border:1px solid #223052; border-radius:8px; padding:10px 8px; text-align:center; }
  .fname { color:#9fc0ff; font-weight:bold; margin-bottom:6px; }
  .fweather { font-size:14px; margin-bottom:4px; }
  .fhelp { color:#7f93ad; font-size:12px; }
</style>
