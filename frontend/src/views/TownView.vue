<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { game, portName } from '../game'

const router = useRouter()

// 每个港口所属海域（与后端 PORTS 一致）
const REGION_OF = {
  london: 'north-sea', amsterdam: 'north-sea', lisbon: 'north-sea', copenhagen: 'north-sea',
  genoa: 'mediterranean', venice: 'mediterranean', athens: 'mediterranean', alexandria: 'mediterranean',
  goa: 'indian', colombo: 'indian', malacca: 'indian',
  guangzhou: 'asia', quanzhou: 'asia', nagasaki: 'asia', manila: 'asia',
}

// 海域基础内容（未单独写过的港口回落到这里）
const BASE = {
  'north-sea': {
    region: '北海',
    intro: '咸湿的海风裹着鲱鱼与柏油的味道，码头工人和东印度公司的职员在酒馆里争论远方的行情。',
    npcs: [
      { name: '老水手巴罗', emoji: '🧔‍♂️', line: '这趟往南走顺风多，跑商的人都在往里斯本赶。', tip: '北海往南的大西洋航线顺风多，短途跑商很划算。' },
      { name: '账房先生霍普', emoji: '🧑‍💼', line: '羊毛、鲱鱼在本地压价，运到地中海才值钱。', tip: '北海的羊毛/鱼货运到地中海诸港往往能翻倍。' },
    ],
    rumors: [
      '📜 传闻：今年鲱鱼大丰收，本地价格怕是要砸下去。',
      '📜 听说南边起了风暴，这几日过海峡的船都绕了远路。',
    ],
  },
  'mediterranean': {
    region: '地中海',
    intro: '阳光把石板路晒得发白，威尼斯商人的贡多拉与阿拉伯人的三角帆在港里挤作一团。',
    npcs: [
      { name: '香料贩老妪', emoji: '🧕', line: '东边来的胡椒、肉桂，进了地中海就被抢空。', tip: '印度洋的香料运进地中海最抢手，利润丰厚。' },
      { name: '落魄骑士', emoji: '🛡️', line: '听说东方遍地是瓷器与丝绸，可惜路太远、海太险。', tip: '丝绸/瓷器最好从东亚直接收，转手几次就贵了。' },
    ],
    rumors: [
      '📜 传闻：威尼斯这季丝绸紧俏，价格一路往上窜。',
      '📜 听说亚历山大港的关税又涨了，过路要留神。',
    ],
  },
  'indian': {
    region: '印度洋',
    intro: '季风一到，满港都是阿拉伯三角帆与郑和式宝船，空气里飘着胡椒与肉桂的辛香。',
    npcs: [
      { name: '阿拉伯舵工', emoji: '🧑‍✈️', line: '顺着西南季风走，果阿到马六甲快得很。', tip: '印度洋诸港之间顺风，航程短、天气也温和。' },
      { name: '香料园主', emoji: '👨‍🌾', line: '胡椒、肉桂在我这便宜，运到欧洲才叫金贵。', tip: '本地香料产地收购，往西运到地中海/北海利润最大。' },
    ],
    rumors: [
      '📜 传闻：今年肉桂减产，收购价怕是要抬一头。',
      '📜 听说马六甲海峡近来不太平，过往船只都结伴而行。',
    ],
  },
  'asia': {
    region: '东亚',
    intro: '青瓷与乌龙茶香漫过码头，白银一箱箱搬上船，市舶司的旗在风里猎猎作响。',
    npcs: [
      { name: '市舶司小吏', emoji: '🧑‍⚖️', line: '瓷器、茶叶、生丝，都是西洋人争抢的宝贝。', tip: '东亚的瓷器/茶叶/生丝运往欧洲，差价最惊人。' },
      { name: '老船长阿福', emoji: '👴', line: '往南洋走要算准季风，逆风可就寸步难行。', tip: '东亚往返印度洋看准季风，别顶风硬闯。' },
    ],
    rumors: [
      '📜 传闻：今年春茶上得好，茶叶在泉州便宜得很。',
      '📜 听说长崎近来盘查严，带太多货怕是要被税。',
    ],
  },
}

// 重点港口的专属内容（覆盖海域基础）
const SPECIFIC = {
  london: {
    intro: '泰晤士河上雾锁两岸，东印度公司的楼船泊在港心，酒馆里人人都在聊远航的买卖。',
    npcs: [
      { name: '东印度公司代理', emoji: '🎩', line: '从东方运来的茶与瓷，在伦敦能卖出天价。', tip: '东亚的瓷器/茶叶运到伦敦利润最高，是远洋主线。' },
      { name: '醉醺醺的渔夫', emoji: '🍺', line: '往里斯本走西风顺，跑一趟比在近海捞鱼强。', tip: '伦敦→里斯本顺风，短途跑商稳赚不赔。' },
    ],
    rumors: [
      '📜 布告：王室悬赏新航路，敢远航的船长不愁没买卖。',
      '📜 传闻：今年羊毛过剩，本地价格怕是要跳水。',
    ],
  },
  lisbon: {
    intro: '大西洋的浪拍着古老的礁石，这里是驶向未知海域的最后一座欧陆港口。',
    npcs: [
      { name: '远征队老兵', emoji: '⚔️', line: '绕过好望角就能到东方，那边的货才叫金贵。', tip: '里斯本是通往印度洋/东亚远洋航线的起点。' },
      { name: '酒馆老板娘', emoji: '👩‍🍳', line: '北边的货在这好卖，南边的香料更抢手。', tip: '北海货物运来好脱手，东方香料在此最抢。' },
    ],
    rumors: [
      '📜 传闻：今年跨洋船队归来，胡椒价格要松动。',
      '📜 布告：出港前记得占卜天气，雷暴季可不好惹。',
    ],
  },
  genoa: {
    intro: '热那亚的塔楼俯瞰着海湾，银行家的金币叮当响，商栈里堆满东方的奇货。',
    npcs: [
      { name: '银行家之子', emoji: '💰', line: '借钱跑商？利息好说，只要你跑得回来。', tip: '地中海是短途跑商天堂，资金周转快。' },
      { name: '丝绸商人', emoji: '🧵', line: '威尼斯的丝、东方的瓷，到我这都能溢价。', tip: '丝绸/瓷器在地中海诸港间倒手利润可观。' },
    ],
    rumors: [
      '📜 传闻：威尼斯商会压价收购，散户都往热那亚跑。',
      '📜 布告：本季关税下调，正是囤货好时机。',
    ],
  },
  venice: {
    intro: '运河纵横的水城，贡多拉载着香料与谣言在楼宇间穿行。',
    npcs: [
      { name: '贡多拉船夫', emoji: '🚣', line: '想去东方？先攒够货，再等顺风季。', tip: '远洋前先在地中海备货，等季风再出发。' },
      { name: '玻璃匠师傅', emoji: '🔮', line: '穆拉诺的琉璃值钱，可运起来易碎怕颠。', tip: '高价值货物占舱少，适合远洋，但小心风浪。' },
    ],
    rumors: [
      '📜 传闻：今年丝路不畅，东方货到港即被抢空。',
      '📜 布告：运河税上调，大批量进货要算清账。',
    ],
  },
  goa: {
    intro: '葡属印度的明珠，教堂的钟声与寺庙的梵音同在一座港口里。',
    npcs: [
      { name: '修道院采买', emoji: '⛪', line: '胡椒、肉桂成船地走，欧洲人离不开这味儿。', tip: '果阿是香料集散地，往西运利润惊人。' },
      { name: '宝船领航员', emoji: '🧭', line: '往东到马六甲、往西回欧洲，都看季风脸色。', tip: '果阿是印度洋枢纽，进出皆顺季风。' },
    ],
    rumors: [
      '📜 传闻：今年胡椒大产，产地价低到离谱。',
      '📜 布告：季风将转，逆风前尽早离港。',
    ],
  },
  guangzhou: {
    intro: '十三行的旗幡招展，西洋商船与本地舢板在珠江口挤成一片，白银叮当入箱。',
    npcs: [
      { name: '十三行掌柜', emoji: '🏮', line: '瓷器、茶叶、生丝，洋人都当宝贝抢。', tip: '广州是东亚货源大本营，装满再走最值。' },
      { name: '疍家渔娘', emoji: '🚤', line: '往南洋顺风就快，顶风可要熬上好多天。', tip: '广州→马六甲顺风短途，返程看准季风。' },
    ],
    rumors: [
      '📜 传闻：春茶新到，茶叶价低，正该囤。',
      '📜 布告：市舶司盘查在即，货单要备齐。',
    ],
  },
  nagasaki: {
    intro: '出岛的荷兰商馆孤悬港中，黑船的影子偶尔掠过海平线。',
    npcs: [
      { name: '出岛通译', emoji: '🗾', line: '这边的瓷与茶，运到欧洲身价百倍。', tip: '长崎货色好，适合做远洋高价货。' },
      { name: '老渔翁', emoji: '🎣', line: '往南去马尼拉顺风，往西回广州要看天。', tip: '长崎↔马尼拉短途顺，远洋看季风。' },
    ],
    rumors: [
      '📜 传闻：今年盘查严，多带货要被重税。',
      '📜 布告：出港先占卜，雷暴季慎行。',
    ],
  },
  manila: {
    intro: '大帆船的母港，每年一度的白银船队在此与东方的货山相遇。',
    npcs: [
      { name: '大帆船水手', emoji: '⛵', line: '美洲的白银换东方的丝与瓷，一趟肥得流油。', tip: '马尼拉聚着东亚货与白银，倒手极赚。' },
      { name: '市集阿婆', emoji: '👵', line: '广州、长崎的货都先到我这，再往上走。', tip: '马尼拉是东亚货物中转站，短线好做。' },
    ],
    rumors: [
      '📜 传闻：今年白银船队迟到，物价有点慌。',
      '📜 布告：台风季临近，远航务必占卜。',
    ],
  },
}

const portId = computed(() => game.you?.port || 'london')
const portName_ = computed(() => portName(portId.value))

const data = computed(() => {
  const specific = SPECIFIC[portId.value]
  const base = BASE[REGION_OF[portId.value] || 'north-sea'] || BASE['north-sea']
  return specific
    ? { region: base.region, ...specific }
    : base
})

const talkLog = ref([])
const activeNpc = ref(null)

function talk(npc) {
  activeNpc.value = npc
  const line = `💬 ${npc.name}：${npc.line}（💡 ${npc.tip}）`
  if (!talkLog.value.includes(line)) talkLog.value.unshift(line)
  if (talkLog.value.length > 12) talkLog.value.pop()
}
</script>

<template>
  <div>
    <div class="panel">
      <b>🏘 {{ portName_ }}居民区</b>
      <p class="hint">{{ data.region }} · {{ data.intro }}</p>
    </div>

    <div class="panel">
      <b>🏚 街坊邻里</b>
      <p class="hint">点开聊聊，街坊的消息有时能点醒一笔好买卖。</p>
      <div class="npcs">
        <button
          v-for="npc in data.npcs"
          :key="npc.name"
          class="npc"
          :class="{ active: activeNpc && activeNpc.name === npc.name }"
          @click="talk(npc)"
        >
          <span class="emoji">{{ npc.emoji }}</span>
          <span class="meta">
            <b>{{ npc.name }}</b>
            <small>{{ npc.line }}</small>
          </span>
        </button>
      </div>

      <div v-if="talkLog.length" class="talk">
        <div v-for="(t, i) in talkLog" :key="i" class="talk-line">{{ t }}</div>
      </div>
    </div>

    <div class="panel">
      <b>📜 布告栏</b>
      <ul class="rumors">
        <li v-for="(r, i) in data.rumors" :key="i">{{ r }}</li>
      </ul>
    </div>

    <div class="panel places">
      <b>🗺 还要去哪</b>
      <div class="grid">
        <button class="place dock" @click="router.push('/dock')">⚓ 码头<small>回到登岸处</small></button>
        <button class="place market" @click="router.push('/')">🏝 市场<small>买卖货物</small></button>
        <button class="place sail" @click="router.push('/map')">🗺 航海图<small>规划下一段航程</small></button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.hint { color:#8899aa; font-size:13px; }
.npcs { display:flex; gap:10px; flex-wrap:wrap; margin-top:8px; }
.npc {
  flex:1; min-width:200px; display:flex; align-items:flex-start; gap:10px;
  padding:12px; border-radius:10px; background:#1c2b4a; text-align:left;
}
.npc.active { outline:2px solid #9fe6c0; }
.npc .emoji { font-size:26px; line-height:1; }
.npc .meta { display:flex; flex-direction:column; }
.npc .meta b { color:#9fe6c0; font-size:15px; }
.npc .meta small { color:#cfd8ea; font-size:12px; margin-top:3px; }
.talk { margin-top:10px; border-top:1px solid #2a3650; padding-top:8px; }
.talk-line { font-size:13px; color:#e7eefc; margin:4px 0; }
.rumors { margin:8px 0 0; padding-left:18px; }
.rumors li { font-size:13px; color:#cfd8ea; margin:5px 0; }
.places { margin-top:10px; }
.grid { display:flex; gap:10px; flex-wrap:wrap; margin-top:10px; }
.place {
  flex:1; min-width:120px; display:flex; flex-direction:column; align-items:flex-start;
  padding:14px; font-size:16px; font-weight:bold; border-radius:10px; text-align:left;
}
.place small { font-weight:normal; font-size:12px; color:#9fb3cf; margin-top:4px; }
.place.dock { background:#22304d; }
.place.market { background:#1c2b4a; }
.place.sail { background:#15303a; }
</style>
