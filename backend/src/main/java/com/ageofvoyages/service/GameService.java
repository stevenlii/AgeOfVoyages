package com.ageofvoyages.service;

import com.ageofvoyages.dto.ChatRequest;
import com.ageofvoyages.dto.LoginRequest;
import com.ageofvoyages.dto.SailDecisionRequest;
import com.ageofvoyages.dto.SailRequest;
import com.ageofvoyages.dto.TradeRequest;
import com.ageofvoyages.dto.TravelRequest;
import com.ageofvoyages.model.Good;
import com.ageofvoyages.model.Player;
import com.ageofvoyages.model.PlayerEntity;
import com.ageofvoyages.model.Port;
import com.ageofvoyages.model.Voyage;
import com.ageofvoyages.model.SeafareEventEntity;
import com.ageofvoyages.repository.PlayerMapper;
import com.ageofvoyages.repository.SeafareConfigMapper;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class GameService {

    // ---------- 行情参数（玩家买卖真实影响价格） ----------
    /** 玩家交易最多把价格推离基准 ±60% */
    private static final double MAX_DEV = 0.6;
    /** 供需敏感度：约 30 单位交易达到半饱和。用 tanh 保证平滑饱和，不会无限涨跌 */
    private static final double SCALE = 30.0;
    /** 每轮刷新（5 分钟）供需冲击向 0 回归的比例，保证价格会慢慢恢复，不会永久崩盘 */
    private static final double REVERT = 0.5;
    /** 价格变动超过该比例才触发全服“暴涨/暴跌”广播 */
    private static final double IMPACT_THRESHOLD = 0.06;

    // ---------- 航行参数（手动模式） ----------
    /** 每次点击「航行」前进的公里数是一个 40~120 的随机区间，会随天气/海况合理分布（见 stepKm） */
    /** 距当前航位多近的其它港口会被视为「途经地」（可进港 / 继续） */
    private static final double PASSING_RADIUS_KM = 220;

    // ---------- 天气：缓慢演变，绝不跨级突变 ----------
    // 严重度阶梯：晴空万里(0) → 多云(1) → 阴雨(2) → {狂风大作, 下雷雨}(3)
    // 每次演变只在“相邻天气”之间移动，所以从「晴空万里」绝不可能一步跳到「下雷雨」。
    private static final Map<String, List<String>> WEATHER_NEXT = Map.of(
            "晴空万里", List.of("晴空万里", "多云"),
            "多云",     List.of("晴空万里", "多云", "阴雨"),
            "阴雨",     List.of("多云", "阴雨", "狂风大作", "下雷雨"),
            "狂风大作", List.of("阴雨", "狂风大作", "下雷雨"),
            "下雷雨",   List.of("阴雨", "狂风大作", "下雷雨")
    );
    private static final Map<String, String> WEATHER_HELP = Map.of(
            "晴空万里", "风和日丽，宜扬帆远航",
            "多云",     "云影舒卷，海面平稳",
            "阴雨",     "细雨绵绵，注意保暖",
            "狂风大作", "狂风呼啸，舵手须谨慎",
            "下雷雨",   "电闪雷鸣，谨防落雷劈船"
    );
    /** 距任何港口超过该公里数才算“海中间”——海盗、雷击等坏事件只发生在真正的大洋上 */
    private static final double DEEP_SEA_KM = 150;
    /** 地球半径（公里），用于大圆距离 / 航线插值 */
    private static final double EARTH_R = 6371.0;

    private final SimpMessagingTemplate tmpl;
    private final PlayerMapper repo;
    private final SeafareConfigMapper cfgRepo;

    // ---------- 海上遭遇（配置在 seafare_event 表，一行一个事件，改库即时生效；以下为表缺行时的兜底值） ----------
    private record SeafareRow(double perKm, String seaReq, double minStartKm, double minSpacingKm,
                              int maxPerVoyage, double minVoyageKm) {}

    /** 每次航行点击从数据库读配置（改库即时生效）；少的行用代码兜底值补齐 */
    private Map<String, SeafareRow> seafareRows() {
        Map<String, SeafareRow> m = new HashMap<>();
        for (SeafareEventEntity e : cfgRepo.findAll()) {
            m.put(e.eventCode, new SeafareRow(e.perKm, e.seaReq == null ? "any" : e.seaReq,
                    e.minStartKm, e.minSpacingKm, e.maxPerVoyage, e.minVoyageKm));
        }
        // 兜底默认：漂流瓶 600km 最多1瓶/前100km不出现/全程≤5；彩蛋 300km 间隔/全程≤10；
        // 海盗 1000km 间隔/全程≤2；雷击 全程只1次且航线≥3000km
        m.putIfAbsent("bottle",    new SeafareRow(0.0017, "any",   100,  600, 5,  0));
        m.putIfAbsent("ambient",   new SeafareRow(0.002,  "any",   0,    300, 10, 0));
        m.putIfAbsent("pirate",    new SeafareRow(0.0015, "ocean", 0,    1000, 2, 0));
        m.putIfAbsent("lightning", new SeafareRow(0.001,  "ocean", 0,    150, 1,  3000));
        return m;
    }

    // ---------- 世界地图（一级：海域 / 二级：港口，带真实经纬度） ----------
    private record Region(String id, String name) {}

    private final List<Region> REGIONS = List.of(
            new Region("north-sea", "西北欧 · 北海"),
            new Region("mediterranean", "地中海"),
            new Region("indian", "印度洋 · 南洋"),
            new Region("asia", "亚洲 · 远东")
    );
    /** 海域的“大洋短名”（航行中「当前海域」展示用） */
    private static final Map<String, String> SEA_NAMES = Map.of(
            "north-sea", "北海",
            "mediterranean", "地中海",
            "indian", "印度洋",
            "asia", "远东"
    );
    private final List<Port> PORTS = List.of(
            new Port("london", "伦敦", "north-sea", 51.5074, -0.1278),
            new Port("amsterdam", "阿姆斯特丹", "north-sea", 52.3676, 4.9041),
            new Port("lisbon", "里斯本", "north-sea", 38.7223, -9.1393),
            new Port("copenhagen", "哥本哈根", "north-sea", 55.6761, 12.5683),

            new Port("genoa", "热那亚", "mediterranean", 44.4056, 8.9463),
            new Port("venice", "威尼斯", "mediterranean", 45.4408, 12.3155),
            new Port("athens", "雅典", "mediterranean", 37.9838, 23.7275),
            new Port("alexandria", "亚历山大", "mediterranean", 31.2001, 29.9187),

            new Port("goa", "果阿", "indian", 15.2993, 74.1240),
            new Port("colombo", "科伦坡", "indian", 6.9271, 79.8612),
            new Port("malacca", "马六甲", "indian", 2.1896, 102.2501),

            new Port("guangzhou", "广州", "asia", 23.1291, 113.2644),
            new Port("quanzhou", "泉州", "asia", 24.8741, 118.6757),
            new Port("nagasaki", "长崎", "asia", 32.7503, 129.8777),
            new Port("manila", "马尼拉", "asia", 14.5995, 120.9842)
    );
    private final List<Good> GOODS = List.of(
            new Good("tea", "茶叶", 100, 0.2),
            new Good("spice", "香料", 200, 0.2),
            new Good("wine", "葡萄酒", 150, 0.2)
    );
    private final Map<String, Port> portMap = PORTS.stream().collect(Collectors.toMap(Port::id, p -> p));
    private final Map<String, Good> goodMap = GOODS.stream().collect(Collectors.toMap(Good::id, g -> g));

    /** 商品的「主产地海域」：在主产地便宜、跨大区贵——这是「跑商赚钱」闭环的根基 */
    private static final Map<String, String> GOOD_HOME_REGION = Map.of(
            "tea", "asia",
            "spice", "indian",
            "wine", "mediterranean"
    );
    /** 「东半球」：indian + asia；其余（north-sea、mediterranean）属「西半球」 */
    private static final Set<String> EAST_HEMISPHERE = Set.of("indian", "asia");

    /** 行情：portId -> (goodId -> MarketItem) */
    private final Map<String, Map<String, MarketItem>> market = new ConcurrentHashMap<>();
    /** 在线玩家（实时状态，内存）：clientId -> Player */
    private final Map<String, Player> players = new ConcurrentHashMap<>();
    /** 每位玩家的组合式叙事器：保证同一玩家反复遭遇同一事件时，描述与内心OS尽量不重样 */
    private final Map<String, Narrator> narrators = new ConcurrentHashMap<>();

    public GameService(SimpMessagingTemplate tmpl, PlayerMapper repo, SeafareConfigMapper cfgRepo) {
        this.tmpl = tmpl;
        this.repo = repo;
        this.cfgRepo = cfgRepo;
    }

    private Narrator narrator(String clientId) {
        return narrators.computeIfAbsent(clientId, k -> new Narrator());
    }

    @PostConstruct
    public void init() {
        refreshMarket();
    }

    /**
     * 行情每 5 分钟刷新一次：
     * 1) drift（时间因素）重新随机，±range；
     * 2) stock（玩家买卖造成的供需）向 0 均值回归，让冲击随时间消退、价格能恢复；
     * 3) 重算价格并推送给所有在线玩家。
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void refreshMarket() {
        for (Port p : PORTS) {
            Map<String, MarketItem> m = market.computeIfAbsent(p.id(), k -> new ConcurrentHashMap<>());
            for (Good g : GOODS) {
                MarketItem it = m.computeIfAbsent(g.id(), k -> new MarketItem(g.base()));
                it.base = g.base();
                it.drift = (Math.random() * 2 - 1) * g.range();
                it.stock *= (1 - REVERT);
                recalc(p.id(), g.id(), it);
            }
        }
        for (String cid : players.keySet()) sendState(cid);
    }

    /** 最终价 = 基准价 × 港口乘数 ×(1+时间波动) ×(1+供需影响)；tanh 保证平滑饱和。
     *  港口乘数让「主产地便宜 / 远港贵」，是跑商赚钱的根基。 */
    private void recalc(String portId, String goodId, MarketItem it) {
        double mult = priceMult(portId, goodId);
        double eff = it.base * mult * (1 + it.drift);
        double stockMult = 1 + MAX_DEV * Math.tanh(-it.stock / SCALE); // stock 为负(被买走)→涨价
        it.buy = Math.max(1, (int) Math.round(eff * stockMult));
        it.sell = Math.max(1, (int) Math.round(it.buy * 0.9));
        it.trend = (int) Math.round((it.buy / (double) it.base - 1) * 100);
    }

    /** 港口对某商品的基准价乘数：主产地 0.55~0.72、同半球相邻 0.95~1.15、跨大区 1.65~1.95。
     *  同海域内不同港口有稳定的小抖动，避免同区价格完全一致。 */
    private double priceMult(String portId, String goodId) {
        Port p = portMap.get(portId);
        if (p == null) return 1.0;
        String portRegion = p.regionId();
        String home = GOOD_HOME_REGION.getOrDefault(goodId, "north-sea");
        double jitter = portJitter(portId, goodId);
        if (portRegion.equals(home)) {
            return 0.55 + 0.17 * jitter;            // 主产地，便宜
        }
        boolean portEast = EAST_HEMISPHERE.contains(portRegion);
        boolean homeEast = EAST_HEMISPHERE.contains(home);
        if (portEast == homeEast) {
            return 0.95 + 0.20 * jitter;            // 同半球相邻，平价
        }
        return 1.65 + 0.30 * jitter;                // 跨大区，贵
    }

    /** 稳定的 [0,1) 抖动，让同海域不同港口的同种货物有差异 */
    private double portJitter(String portId, String goodId) {
        int h = Math.abs((portId + ":" + goodId).hashCode());
        return (h % 1000) / 1000.0;
    }

    // ---------- 指令 ----------

    public void login(LoginRequest r) {
        Player p = players.get(r.clientId());
        if (p == null) {
            // 本进程第一次见到该玩家：从 MySQL 恢复已保存进度
            p = new Player();
            PlayerEntity e = repo.findById(r.clientId());
            if (e != null) {
                p.name = e.name;
                p.gold = e.gold;
                p.port = e.port;
                p.cargoCap = e.cargoCap;
                p.cargo = new HashMap<>(e.cargo);
                // 旧版自动航行标记：重启后一律回到出发港，手动航行不跨重启恢复
                if (e.traveling) {
                    p.traveling = false;
                }
            }
            players.put(r.clientId(), p);
        }
        // 若玩家已在内存中（同一浏览器刷新 / 开第二个标签页），保留其进行中的航程与金币，
        // 不再用数据库里的旧值覆盖——否则航行途中捡到的钱会被“抹掉”。
        if (r.name() != null && !r.name().isBlank()) p.name = r.name();
        persist(p, r.clientId());
        sendState(r.clientId());
        broadcast("船长 [" + p.name + "] 登陆了 " + portName(p.port) + "港");
    }

    /** 从地图选择目的地（手动航行）：先规划航线，点击「出发」才算启航 */
    public void travel(TravelRequest r) {
        Player p = players.get(r.clientId());
        if (p == null) return;
        if (p.voyage != null) { sendMsg(r.clientId(), "已有未完成的航程，请先到港或返回后再选择新航线"); return; }
        Port from = portMap.get(p.port);
        Port dest = portMap.get(r.to());
        if (dest == null) { sendMsg(r.clientId(), "目标港口无效"); return; }
        if (dest.id().equals(p.port)) { sendMsg(r.clientId(), "您已在该港"); return; }

        double total = havKm(from.lat(), from.lng(), dest.lat(), dest.lng());

        Voyage v = new Voyage();
        v.fromId = p.port;
        v.destId = dest.id();
        v.totalKm = total;
        v.traveledKm = 0;
        v.lat = from.lat();
        v.lng = from.lng();
        v.departed = false;
        addLog(v,"🗺 航线已规划：由 " + from.name() + " 前往 " + dest.name()
                + "，全程约 " + (int) Math.round(total) + " 公里。点击「出发」启程！"
                + "（每次航行的里程随天气海况浮动，途中可用「天气占卜」预知前景）");
        p.voyage = v;
        p.traveling = true;
        p.travelingTo = dest.id();
        p.arriveAt = 0;
        persist(p, r.clientId());
        sendState(r.clientId());
    }

    /** 手动航行：dir=forward 前进 10 公里（首次点击＝启航出发），dir=back 掉头后退 10 公里。沿途随机遭遇天气事件。 */
    public void sail(SailRequest r) {
        Player p = players.get(r.clientId());
        if (p == null) return;
        Voyage v = p.voyage;
        if (v == null) { sendMsg(r.clientId(), "当前未在航行"); return; }
        if (v.pendCombat) {
            sendMsg(r.clientId(), "海盗就横在船头，先拿个主意：迎战 / 甩开 / 花钱消灾");
            return;
        }
        if (v.offeredId != null) {
            sendMsg(r.clientId(), "前方正在经过 " + portName(v.offeredId) + "，请选择「进港」或「继续航行」");
            return;
        }

        boolean back = "back".equals(r.dir());
        Port dest = portMap.get(v.destId);
        Port from = portMap.get(v.fromId);
        if (dest == null || from == null) { p.voyage = null; return; }

        double prevLat = v.lat, prevLng = v.lng;

        if (back) {
            // 掉头后退：回到出发港即航程结束；途中靠近的港仍可进港
            if (!v.departed) { sendMsg(r.clientId(), "还未出发，无需后退"); return; }
            double step = Math.min(stepKm(p.weather), v.traveledKm);
            double brng = bearingDeg(v.lat, v.lng, from.lat(), from.lng());
            double[] np = destPoint(v.lat, v.lng, brng, step);
            v.lat = np[0];
            v.lng = np[1];
            v.traveledKm = Math.max(0, v.traveledKm - step);
            seaOf(v);

            // 后退时离某个「已略过」的港越来越远，就把它重新解锁，之后再次靠近可重新选择
            for (String pid : List.copyOf(v.passed)) {
                Port po = portMap.get(pid);
                if (po == null) continue;
                if (havKm(v.lat, v.lng, po.lat(), po.lng()) > PASSING_RADIUS_KM) v.passed.remove(pid);
            }

            if (v.traveledKm <= 1e-9) {
                addLog(v,"🔄 你掉头返航，重新靠上 " + from.name() + " 的码头。");
                arriveAt(p, r.clientId(), v, from.id(), "返航回到出发港 " + from.name() + "（全程未走完）");
                return;
            }
            addLog(v,"⏪ 后退 " + (int) Math.round(step) + " 公里，距目的地还有 " + (int) Math.round(v.totalKm - v.traveledKm) + " 公里");
            trim(v.seaLog);
            detectWaypoint(v, prevLat, prevLng);
            sendState(r.clientId());
            return;
        }

        // 前进（首次点击＝启航出发）：出发前若海况恶劣，先提醒一次，不真正开船
        String beforeWeather = p.weather;                 // 本次点击前的天气（用于判断是否“变天”）
        boolean justDeparted = !v.departed;
        // 天气每击缓慢渐变（12% 概率，只走相邻天气，绝不变天跳级）：长航线途中也会遇到风暴，
        // 雷击这类“大洋深处”的坏事件才不会永远可望而不可即
        String weather = driftWeather(p);
        if (justDeparted) {
            if (isExtreme(weather) && !v.weatherWarned) {
                v.weatherWarned = true;
                addLog(v,"⚠️ 港外海况恶劣（" + weather + "），现在出航太危险！若执意要开船，请再按一次「出发」；想改期就点「返回」留在港里。");
                trim(v.seaLog);
                sendState(r.clientId());
                return;
            }
            // 出发前一刻也漂移一次：好让玩家在码头时能有“阴到晴/晴到阴”的自然变化
        }
        v.departed = true;

        if (beforeWeather != null && !beforeWeather.equals(weather)) {
            addLog(v,weatherShift(r.clientId(), beforeWeather, weather));
        }

        double step = Math.min(stepKm(weather), v.totalKm - v.traveledKm);
        double brng = bearingDeg(v.lat, v.lng, dest.lat(), dest.lng());
        double[] np = destPoint(v.lat, v.lng, brng, step);
        v.lat = np[0];
        v.lng = np[1];
        v.traveledKm += step;
        seaOf(v);

        if (justDeparted) addLog(v,"⛵ 拔锚启航！船头劈开浪花，驶向 " + dest.name());

        if (v.traveledKm >= v.totalKm - 1e-9) {
            addLog(v,"🏝 抵达 " + dest.name() + "港！航程结束。");
            arriveAt(p, r.clientId(), v, dest.id(), "🏝 历经 " + (int) Math.round(v.traveledKm) + " 公里航行，抵达 " + dest.name() + "港");
            broadcast("🏝 船长 [" + p.name + "] 历经" + (int) Math.round(v.traveledKm) + "公里航行，抵达 " + dest.name() + "港");
            return;
        }

        addLog(v,seaReport(v, weather));                       // 当前状态：距目的地剩余公里 + 天气 + 海面
        addLog(v,weatherMood(r.clientId(), weather, v));        // 天气连着心情（同时写入 v.mood 供界面显示）
        v.event = "🌤 航行中，海面暂无异常";
        rollSeaEvent(p, r.clientId(), v, weather, step);           // 最多一个遭遇：漂流瓶/彩蛋/海盗/雷击
        persist(p, r.clientId());                                  // 事件增减的金币/货物立即入库，刷新或重连不会丢

        // 检测前方途经地
        detectWaypoint(v, prevLat, prevLng);

        trim(v.seaLog);
        sendState(r.clientId());
    }

    /** 航程结束公共收尾：靠岸抵达某一港口，并落库 */
    private void arriveAt(Player p, String clientId, Voyage v, String arrivePort, String msg) {
        p.port = arrivePort;
        p.voyage = null;
        p.traveling = false;
        p.travelingTo = null;
        p.arriveAt = 0;
        persist(p, clientId);
        tmpl.convertAndSend("/topic/player/" + clientId + "/voyageLog", Map.of("lines", List.copyOf(v.seaLog)));
        sendState(clientId);
        sendMsg(clientId, msg);
    }

    /** 航行决策：enter 靠岸进港（此行结束） / continue 继续航行 / return 取消航线或返回出发港 */
    public void sailDecision(SailDecisionRequest r) {
        Player p = players.get(r.clientId());
        if (p == null) return;
        Voyage v = p.voyage;
        if (v == null) { sendMsg(r.clientId(), "当前没有进行中的航程"); return; }

        String pid = v.offeredId;
        if ("return".equals(r.choice()) && !v.pendCombat) {
            boolean preDepart = !v.departed;
            String backPort = v.fromId;
            addLog(v,"↩ " + (preDepart ? "你收起航海图，放弃了这条航线。" : "你调转船头，掉头回航。"));
            arriveAt(p, r.clientId(), v, backPort,
                    preDepart ? "已取消航线，留在 " + portName(backPort) + "港" : "返回出发港 " + portName(backPort));
            broadcast("↩ 船长 [" + p.name + "] " + (preDepart ? "取消了" + portName(v.destId) + "的航线" : "中途返回了 " + portName(backPort) + "港"));
            return;
        }

        // 海盗对峙：玩家在「迎战 / 甩开 / 花钱消灾」中三选一
        if (v.pendCombat) {
            Narrator n = narrator(r.clientId());
            String c = r.choice();
            List<String> lines = new ArrayList<>();
            if ("fight".equals(c)) {
                boolean win = Math.random() < 0.5;
                if (win) {
                    int loot = 5 + (int) (Math.random() * 11);    // 5~15 金币
                    p.gold += loot;
                    lines.add("⚔️ 你拔刀迎战！混战中砍翻了一片，海盗头子一看风向不对，扔下赃物扬帆跑了。");
                    lines.add("💰 从海盗赃物里翻出 " + loot + " 金币！");
                    lines.add("（内心OS：*" + n.slot("pirWinOs",
                            "嘿，爷爷的刀还没老！这帮孙子下次再撞上就没这么好运了！",
                            "痛快！这才叫跑海的人该有的样子！今晚必须开两瓶好酒！",
                            "我擦刀上的血，心里那股憋屈总算散了一半。")
                            + "*）");
                } else {
                    int used = p.cargo.values().stream().mapToInt(Integer::intValue).sum();
                    if (used > 0) {
                        int take = 1 + (int) (Math.random() * 3); // 1~3 件
                        int left = Math.min(take, used);
                        List<String> lostDesc = new ArrayList<>();
                        // 用 keySet 快照遍历：下面会 remove/put，直接迭代 entrySet 会抛 ConcurrentModificationException
                        for (String key : List.copyOf(p.cargo.keySet())) {
                            if (left <= 0) break;
                            int have = p.cargo.getOrDefault(key, 0);
                            int x = Math.min(have, left);
                            if (x <= 0) continue;
                            int remain = have - x;
                            if (remain <= 0) p.cargo.remove(key); else p.cargo.put(key, remain);
                            lostDesc.add(goodName(key) + "×" + x);
                            left -= x;
                        }
                        lines.add("⚔️ 你拔刀迎战！可他们人多势众，你被打翻在地，舱门被砸开。");
                        lines.add("📦 货物被抢走 " + String.join("、", lostDesc) + "！");
                    } else {
                        lines.add("⚔️ 你拔刀迎战！可他们人多势众，你被打翻在地。");
                        lines.add("　好在舱里空空，他们翻了个寂寞，骂骂咧咧撤了。");
                    }
                    lines.add("（内心OS：*" + n.slot("pirLoseOs",
                            "刀都卷了刃……这仇我记下了，下回非得装两门炮再走这条道！",
                            "人在舱檐下，不得不低头。可这口气，我是真咽不下去……",
                            "我盯着他们离去的船影，把每个刺青都刻进了脑子里。",
                            "呜……这趟本钱赔了大半，心都在滴血！")
                            + "*）");
                }
            } else if ("flee".equals(c)) {
                int lost = 80 + (int) (Math.random() * 71);        // 倒退 80~150 公里
                lost = Math.min(lost, (int) Math.round(v.traveledKm));
                v.traveledKm = Math.max(0, v.traveledKm - lost);
                lines.add("🏃 你扯满帆、调转船头，趁乱甩开了追兵。");
                lines.add("⏪ 可惜被追回了 " + lost + " 公里——海盗对这片海域熟得很。");
                lines.add("（内心OS：*" + n.slot("pirFleeOs",
                        "好汉不吃眼前亏，先保住货要紧。这口气……来日方长！",
                        "跑得心跳都到嗓子眼了……这风救了我一命啊。",
                        "等我回港就去找那帮老水手问问，这片海盗是哪路神仙，回头一定找回场子。")
                        + "*）");
            } else if ("pay".equals(c)) {
                int toll = 5;
                if (p.gold < toll) {
                    sendMsg(r.clientId(), "你身上凑不够 5 金币，海盗嫌你没油水，骂骂咧咧不肯走。赶紧选「迎战」或「甩开」！");
                    return;
                }
                p.gold -= toll;
                lines.add("💰 你忍气吞声，乖乖交出 " + toll + " 金币。海盗掂了掂成色，撇撇嘴挥手放你走了。");
                lines.add("（内心OS：*" + n.slot("pirPayOs",
                        "花钱消灾，花钱消灾……我数着空钱袋，拼命劝自己冷静。",
                        "人在屋檐下，不得不低头。这笔账……记着吧。",
                        "呸！等我发了财，第一件事就是雇条炮船来收拾你们！")
                        + "*）");
            } else {
                sendMsg(r.clientId(), "海盗正等着你拿主意：迎战 / 甩开 / 花钱消灾");
                return;
            }
            v.pendCombat = false;
            addAllLog(v, lines);
            v.eventDetail = List.copyOf(lines);
            // 注意：lastPirateKm 已在触发时记过，此处不覆盖——否则「甩开」后退后很快又会被海盗拦一次
            trim(v.seaLog);
            tmpl.convertAndSend("/topic/player/" + r.clientId() + "/voyageLog", Map.of("lines", List.copyOf(v.seaLog)));
            persist(p, r.clientId());
            sendState(r.clientId());
            return;
        }

        if (pid == null) { sendMsg(r.clientId(), "当前没有可选择的途经地"); return; }

        if ("enter".equals(r.choice())) {
            p.port = pid;
            p.voyage = null;
            p.traveling = false;
            p.travelingTo = null;
            persist(p, r.clientId());
            sendState(r.clientId());
            sendMsg(r.clientId(), "已靠岸进入 " + portName(pid) + "港（航程结束）");
            broadcast("🏝 船长 [" + p.name + "] 途中靠岸，进入 " + portName(pid) + "港");
        } else {
            v.passed.add(pid);
            v.offeredId = null;
            addLog(v,"🚢 略过 " + portName(pid) + "，继续向目的地航行");
            trim(v.seaLog);
            sendState(r.clientId());
            sendMsg(r.clientId(), "继续向前航行");
        }
    }

    public void trade(TradeRequest r) {
        Player p = players.get(r.clientId());
        if (p == null) return;
        if (p.voyage != null) { sendMsg(r.clientId(), "航行中无法交易"); return; }
        Good g = goodMap.get(r.item());
        Map<String, MarketItem> pm = market.get(p.port);
        if (g == null || pm == null || !pm.containsKey(r.item())) { sendMsg(r.clientId(), "货物无效"); return; }

        int count;
        try { count = Integer.parseInt(r.count()); } catch (Exception e) { sendMsg(r.clientId(), "数量无效"); return; }
        if (count <= 0) { sendMsg(r.clientId(), "数量无效"); return; }

        MarketItem item = pm.get(r.item());
        int priceBefore = item.buy;

        if ("buy".equals(r.action())) {
            int cost = item.buy * count;
            if (p.gold < cost) { sendMsg(r.clientId(), "金币不足"); return; }
            int used = p.cargo.values().stream().mapToInt(Integer::intValue).sum();
            if (used + count > p.cargoCap) { sendMsg(r.clientId(), "船舱已满"); return; }
            p.gold -= cost;
            p.cargo.merge(r.item(), count, Integer::sum);
            item.stock -= count;   // 货物被买走 → 供给减少 → 涨价
            sendMsg(r.clientId(), "买入 " + g.name() + " x" + count + "，花费 " + cost);
        } else if ("sell".equals(r.action())) {
            if (p.cargo.getOrDefault(r.item(), 0) < count) { sendMsg(r.clientId(), "货物不足"); return; }
            int gain = item.sell * count;
            p.gold += gain;
            p.cargo.merge(r.item(), -count, Integer::sum);
            if (p.cargo.getOrDefault(r.item(), 0) <= 0) p.cargo.remove(r.item());
            item.stock += count;   // 货物被抛售 → 供给增加 → 跌价
            sendMsg(r.clientId(), "卖出 " + g.name() + " x" + count + "，获得 " + gain);
        } else {
            sendMsg(r.clientId(), "未知操作");
            return;
        }

        recalc(p.port, r.item(), item);
        double change = (item.buy - priceBefore) / (double) priceBefore;
        persist(p, r.clientId());

        if (Math.abs(change) >= IMPACT_THRESHOLD) {
            // 价格波动明显：全服广播“暴涨/暴跌”（文档 Day5 的灵魂机制）
            String verb = "buy".equals(r.action()) ? "大肆抢购" : "大肆抛售";
            String dir = change > 0 ? "暴涨" : "暴跌";
            broadcast("📊 船长 [" + p.name + "] 在 " + portName(p.port) + " " + verb + " " + g.name()
                    + " x" + count + "，导致当地" + g.name() + "价格" + dir + "至 " + item.buy + " 金币！");
        } else {
            broadcast("💰 船长 [" + p.name + "] 在 " + portName(p.port)
                    + ("buy".equals(r.action()) ? " 买入 " : " 卖出 ") + g.name() + " x" + count);
        }

        // 同港口的所有在线玩家都要看到新行情，而不只是操作者本人
        sendStateToAllAt(p.port);
    }

    public void chat(ChatRequest r) {
        Player p = players.get(r.clientId());
        String name = (p != null) ? p.name : "无名船长";
        if (r.text() == null || r.text().isBlank()) return;
        broadcast("💬 [" + name + "]: " + r.text());
    }

    // ---------- 地理计算 ----------

    /** 大圆距离（Haversine）：两点间公里数 */
    private static double havKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_R * Math.asin(Math.sqrt(a));
    }

    /** 初始方位角（度） */
    private static double bearingDeg(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1), phi2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** 从 (lat1,lon1) 沿方位角 brng 前进 dKm 公里后的新经纬度 [lat, lng] */
    private static double[] destPoint(double lat1, double lon1, double brngDeg, double dKm) {
        double phi1 = Math.toRadians(lat1), d = dKm / EARTH_R, brng = Math.toRadians(brngDeg);
        double phi2 = Math.asin(Math.sin(phi1) * Math.cos(d) + Math.cos(phi1) * Math.sin(d) * Math.cos(brng));
        double lon2 = Math.toRadians(lon1)
                + Math.atan2(Math.sin(brng) * Math.sin(d) * Math.cos(phi1),
                Math.cos(d) - Math.sin(phi1) * Math.sin(phi2));
        return new double[]{Math.toDegrees(phi2), Math.toDegrees(lon2)};
    }

    /** 当前航况报告：只报「距目的地还有多少公里」+ 天气 + 海面（不再显示“已航行 / 还需 N 次”） */
    private String seaReport(Voyage v, String weather) {
        String[] sea = {"风平浪静", "微波荡漾", "小浪翻涌", "大浪起伏", "巨浪滔天"};
        String s = sea[(int) (Math.random() * sea.length)];
        boolean thunder = "下雷雨".equals(weather);
        int remain = Math.max(0, (int) Math.round(v.totalKm - v.traveledKm));
        return "🌊 距目的地还有 " + remain + " 公里 ｜ 海域：" + seaOf(v) + " ｜ 天气：" + weather + " ｜ 海面：" + s
                + " ｜ 雷雨：" + (thunder ? "🌩 电闪雷鸣" : "无");
    }

    /** 天气缓慢演变：约 88% 维持不变；要变也只在「相邻天气」之间移动
     *  （晴空万里→多云→阴雨→狂风大作/下雷雨），绝不跨级突变（晴空不可能一步跳到雷电）。 */
    private String driftWeather(Player p) {
        if (p.weather == null) return p.weather = "晴空万里";
        if (Math.random() < 0.12) {
            List<String> next = WEATHER_NEXT.get(p.weather);
            if (next != null) p.weather = next.get((int) (Math.random() * next.size()));
        }
        return p.weather;
    }

    /** 本次「航行」前进的公里数：落在 40~120 的随机区间，但随天气/海况合理分布
     *  ——好天气扬帆快，风暴里寸步难行；区间上下界都落在 [40,120]，结果自然不出界。 */
    private double stepKm(String weather) {
        double lo, hi;
        switch (weather == null ? "" : weather) {
            case "晴空万里" -> { lo = 75;  hi = 120; }
            case "多云"     -> { lo = 60;  hi = 120; }
            case "阴雨"     -> { lo = 45;  hi = 100; }
            case "狂风大作", "下雷雨" -> { lo = 40; hi = 85; }
            default        -> { lo = 50;  hi = 110; }
        }
        return lo + Math.random() * (hi - lo);
    }

    /** 是否算“恶劣海况”（出发前会就这种情况先询问是否真的要开船） */
    private boolean isExtreme(String weather) {
        return "下雷雨".equals(weather) || "狂风大作".equals(weather);
    }

    /** 当前航位离最近港口多少公里（判断是否已到“海中间”） */
    private double minPortKm(Voyage v) {
        double best = Double.MAX_VALUE;
        for (Port po : PORTS) {
            best = Math.min(best, havKm(v.lat, v.lng, po.lat(), po.lng()));
        }
        return best;
    }

    /** 当前所在海域：按最近的港口归属到某个大洋（北海/地中海/印度洋/远东），离任何港口≥DEEP_SEA_KM 视为「大洋深处」。
     *  写入 v.seaName（如“印度洋 · 大洋深处”），返回供海况报告拼接。坏事件（海盗/雷击）只在“大洋深处”发生。 */
    private String seaOf(Voyage v) {
        Port near = null;
        double best = Double.MAX_VALUE;
        for (Port po : PORTS) {
            double d = havKm(v.lat, v.lng, po.lat(), po.lng());
            if (d < best) { best = d; near = po; }
        }
        String sea = near == null ? "远洋" : SEA_NAMES.getOrDefault(near.regionId(), near.regionId());
        v.seaName = best >= DEEP_SEA_KM ? sea + " · 大洋深处" : sea + " · 近海";
        return v.seaName;
    }

    // ---------- 航行遭遇（RPG 叙述：组合式词池，场景 + 事件 + 内心OS，喜怒哀乐俱全） ----------
    // 每个事件拆成多个“槽位”，每个槽位配一组台词，组合相乘出大量不同表述；
    // 且同一玩家在同一槽位短时间内不会重复同一句（Narrator 轮转器保证）。

    /** 本次海上氛围：天气决定「天象 + 海况」两段，每次组合不同 */
    private String sceneOf(String clientId, String weather) {
        Narrator n = narrator(clientId);
        return switch (weather) {
            case "下雷雨" -> n.slot("scA_下雷雨",
                    "天像被泼了墨，沉甸甸地压向海面",
                    "黑云翻涌，几乎贴着桅杆",
                    "墨云低垂，电光在云缝里忽明忽灭",
                    "暴雨如幕，天地间一片混沌")
                    + "，" + n.slot("scB_下雷雨",
                    "浪头一个接一个砸向船舷，木料吱嘎作响",
                    "雨水糊住眼睛，甲板上很快积起没脚的水",
                    "雷声滚过海面，震得人耳膜发麻",
                    "船身在浪谷里颠起又跌下");
            case "狂风大作" -> n.slot("scA_狂风大作",
                    "风像是发了疯，呜呜地吼",
                    "冷风灌进领口，吹得人站不稳",
                    "海风呼啸，浪花成片地泼上甲板",
                    "天低云暗，风一阵紧过一阵")
                    + "，" + n.slot("scB_狂风大作",
                    "白浪翻滚，帆被扯得绷成一张弓",
                    "浪尖的飞沫劈头盖脸砸下来，又咸又涩",
                    "船身剧烈地摇，桅杆发出叫人牙酸的呻吟",
                    "你死死攥着缆绳，指甲都要掐进掌心");
            case "阴雨" -> n.slot("scA_阴雨",
                    "冷雨萧萧，没完没了地落",
                    "雨丝斜斜地织成一张灰网",
                    "天色阴沉，雨点敲在甲板上笃笃响",
                    "海面雾蒙蒙的，这雨下得又细又密")
                    + "，" + n.slot("scB_阴雨",
                    "海平线模糊不见，十步之外就看不清",
                    "水汽浸进衣衫，骨头缝里都透着凉",
                    "远处偶尔飘过一抹若隐若现的船影",
                    "浪头闷闷地涌，把船摇得人直犯困");
            case "多云" -> n.slot("scA_多云",
                    "铅云低垂，压得海天都矮了一截",
                    "云层厚厚地堆着，透不出几丝光",
                    "天光晦暗，云影在海上缓缓流动",
                    "云缝里漏下几道懒洋洋的日光")
                    + "，" + n.slot("scB_多云",
                    "海面泛着暗青色，安静得发闷",
                    "空气里盘旋着一股潮湿的土腥味",
                    "起伏的浪也变得迟缓，船走得懒懒的",
                    "海色沉沉，像是揣着什么心事");
            default -> n.slot("scA_晴空万里",
                    "碧空如洗，日头高照",
                    "天高云淡，阳光洒在甲板上",
                    "云影稀薄，海天一片澄蓝",
                    "晴空明朗，风里都带着暖意")
                    + "，" + n.slot("scB_晴空万里",
                    "海面如镜，波光粼粼",
                    "浪头温柔地拱着船底，哗啦作响",
                    "海鸥追着船尾盘旋，叫声清亮",
                    "空气里飘着淡淡的咸香，连帆都懒洋洋的");
        };
    }

    /** 每个前进航次最多掷出【一个】事件（互斥，避免“漂流瓶+海盗”挤在同一击里）：
     *  各类事件的参数存 seafare_event 表（每公里概率 × 本次推进公里数 得本击触发概率）：
     *    - 漂流瓶：走出一段路（min_start_km，如 100km）后才会捞到；600 公里内最多一次；全程≤5
     *    - 彩蛋（飞鱼/海豚/海鸟…）：海上小插曲，300 公里内最多一次；全程≤10
     *    - 海盗：sea_req=ocean 只在大洋深处（离任何港口 ≥150km）；1000 公里内最多一次；全程≤2
     *    - 雷击：sea_req=ocean + 雷雨天；航线 ≥3000km 才会遇到；全程只 1 次 */
    private void rollSeaEvent(Player p, String clientId, Voyage v, String weather, double step) {
        Map<String, SeafareRow> cfg = seafareRows();
        boolean deepSea = minPortKm(v) >= DEEP_SEA_KM;   // 海中间（远离一切港口）

        SeafareRow bottle = cfg.get("bottle");
        // 用“本次推进前”的里程判断：保证整段都开过 min_start_km 后才可能捞到（开局大步一脚也不会第一击就白捡）
        boolean bottleOk = bottle != null
                && v.traveledKm - step >= bottle.minStartKm()                    // 走过一段路才出现
                && v.traveledKm - v.lastBottleKm >= bottle.minSpacingKm()         // 600 公里内最多一次
                && v.bottleCount < bottle.maxPerVoyage;                           // 全程最多 5 次
        double bottleP = bottleOk ? bottle.perKm() * step : 0;

        SeafareRow ambient = cfg.get("ambient");
        boolean ambientOk = ambient != null
                && v.traveledKm - v.lastAmbientKm >= ambient.minSpacingKm()       // 300 公里内最多一次
                && v.ambientCount < ambient.maxPerVoyage;                         // 全程最多 10 次
        double ambientP = ambientOk ? ambient.perKm() * step : 0;

        SeafareRow pirate = cfg.get("pirate");
        boolean pirateOk = pirate != null && "ocean".equals(pirate.seaReq()) && deepSea
                && v.traveledKm - v.lastPirateKm >= pirate.minSpacingKm()         // 1000 公里内最多一次
                && v.pirateCount < pirate.maxPerVoyage;                           // 全程最多 2 次
        double pirateP = pirateOk ? pirate.perKm() * step : 0;

        SeafareRow lightning = cfg.get("lightning");
        boolean lightOk = lightning != null && "ocean".equals(lightning.seaReq()) && "下雷雨".equals(weather) && deepSea
                && v.traveledKm - v.lastLightningKm >= lightning.minSpacingKm()
                && v.lightningCount < lightning.maxPerVoyage                      // 全程只 1 次
                && v.totalKm >= lightning.minVoyageKm();                          // 航线 ≥3000km 才会遇到
        double lightP = lightOk ? lightning.perKm() * step : 0;

        double cumBottle = bottleP + pirateP + lightP + ambientP;
        if (cumBottle <= 0 || Math.random() >= Math.min(1.0, cumBottle)) return;

        double r = Math.random() * cumBottle;
        if (r < pirateP) {
            pirateEvent(p, clientId, v);
            v.lastPirateKm = v.traveledKm;
            v.pirateCount++;
            v.event = "🏴 海盗来袭！";
        } else if (r < pirateP + lightP) {
            lightningEvent(p, clientId, v);
            v.lastLightningKm = v.traveledKm;
            v.lightningCount++;
            v.event = "⚡ 遭遇雷击，货舱受损！";
        } else if (r < pirateP + lightP + bottleP) {
            bottleEvent(p, clientId, v);
            v.lastBottleKm = v.traveledKm;
            v.bottleCount++;
            v.event = "💰 捡到一只漂流瓶";
        } else {
            addLog(v,ambientEvent(clientId, weather));
            v.lastAmbientKm = v.traveledKm;
            v.ambientCount++;
            v.event = "🌊 海上见了些妙趣";
        }
    }

    /** 天气占卜：预报未来 3 天海况（只模拟演变、不改写真实天气），推给专属 topic 由前端弹窗展示。 */
    public void forecast(String clientId) {
        Player p = players.get(clientId);
        if (p == null) return;
        if (p.voyage == null) { sendMsg(clientId, "只有航行途中才能占卜天气"); return; }
        String w = (p.weather == null) ? "晴空万里" : p.weather;
        List<Map<String, String>> days = new ArrayList<>();
        for (int d = 1; d <= 3; d++) {
            List<String> next = WEATHER_NEXT.get(w);
            if (next == null) w = "晴空万里";
            else w = next.get((int) (Math.random() * next.size()));   // 占卜更偏向“给出变化”，不像真实航行那样大概率维持
            days.add(Map.of("day", "第 " + d + " 天", "weather", w, "help", WEATHER_HELP.getOrDefault(w, "")));
        }
        tmpl.convertAndSend("/topic/player/" + clientId + "/forecast",
                Map.of("from", (p.weather == null ? "晴空万里" : p.weather), "days", days));
    }

    /** 天气连着心情：晴好多云则心情大好、哼两句船歌；坏天气则心里打鼓，怕有什么不测。
     *  返回的整段叙事进航海日志；同时把「心情OS」单独写入 v.mood 供界面常驻显示。 */
    private String weatherMood(String clientId, String weather, Voyage v) {
        Narrator n = narrator(clientId);
        String scene = sceneOf(clientId, weather);
        String mood = switch (weather) {
            case "下雷雨" -> n.slot("mood_下雷雨",
                    "心里直打鼓，总觉得这闷雷底下憋着一件说不清的事",
                    "海神爷，今日要是闹脾气，可千万别冲我这艘小破船来",
                    "一边掌舵一边嘀咕：这鬼天气，可别出什么岔子啊",
                    "你攥紧缆绳，心跳得一下比一下沉");
            case "狂风大作" -> n.slot("mood_狂风大作",
                    "心提到了嗓子眼，两条腿都在发软",
                    "风大到想骂街，可一张口就被灌了个满怀",
                    "你把帽檐往下压了压，在心里把各路神仙求了个遍",
                    "手死死攥着船舷，一步都不敢挪");
            case "阴雨" -> n.slot("mood_阴雨",
                    "心里闷闷的，这雨下得人都蔫了",
                    "湿嗒嗒的没个清爽劲儿，你只盼着快点靠岸",
                    "雨打在脸上凉飕飕的，说不出的没精打采",
                    "你缩了缩脖子，长叹一声：这雨，怎么还没个头");
            case "多云" -> n.slot("mood_多云",
                    "云影凉快，海风也温和，整个人松松快快的",
                    "不晒不燥的好天气，你哼起了老家的船歌",
                    "心思跟着云影飘走了一会儿，又落回舵上来",
                    "你眯着眼望天，觉得这么一直开下去也不错");
            default -> n.slot("mood_晴空万里",
                    "太阳晒得人骨头都软了，你忍不住亮开嗓子唱了两句",
                    "好天配好心情，你盘算着这一趟稳赚不赔",
                    "风暖浪平，你悠哉得就差来口小酒了",
                    "海天一色的晴，连迈出的步子都要轻快几分");
        };
        if (v != null) v.mood = mood;
        return scene + "。" + mood;
    }

    /** 天气渐变时的过渡描述（顺着新天气的词池走，变化也不生硬） */
    private String weatherShift(String clientId, String from, String to) {
        Narrator n = narrator(clientId);
        return switch (to) {
            case "下雷雨" -> "🌧 天转眼沉了下来——" + n.slot("shift_下雷雨",
                    "乌云像盖布一样漫过头顶",
                    "风里卷进潮气，远处隐约滚起雷声",
                    "雨点子没跟人商量，劈头就砸了下来");
            case "狂风大作" -> "🌬 风势陡然拔高——" + n.slot("shift_狂风大作",
                    "浪头开始发野，一排高过一排",
                    "船身被推得猛地一歪",
                    "帆被扯得嘶啦作响，绳索蹦得笔直");
            case "阴雨" -> "🌥 日头慢慢隐进云里——" + n.slot("shift_阴雨",
                    "雨丝细细地飘了下来，没完没了",
                    "天蒙上一层灰，海也跟着失了颜色",
                    "潮气从海面漫上来，凉丝丝地贴着皮肤");
            case "多云" -> "🌤 天光柔和了下来——" + n.slot("shift_多云",
                    "云影慢悠悠地飘过，时明时暗",
                    "日头躲在云后，晒得不那么凶了",
                    "风里多了几分清爽");
            default -> "🌞 云开雾散——" + n.slot("shift_晴空",
                    "日头暖洋洋地晒下来，海面亮闪闪地铺开",
                    "风一下温顺了，帆都舒展开来",
                    "天蓝得像洗过，一眼望不到头");
        };
    }

    /** 海上小彩蛋：晴好有鸟有鱼，坏天有浪有雷 —— 稳定又随机地冒出来，让航行不枯燥 */
    private String ambientEvent(String clientId, String weather) {
        Narrator n = narrator(clientId);
        return switch (weather) {
            case "下雷雨" -> n.slot("amb_下雷雨",
                    "⚡ 一道闪电劈在远处海面，溅起老高的水花",
                    "🌩 乌云深处闷雷滚滚，一声比一声近",
                    "🌧 风卷着雨点砸在脸上，火辣辣地疼");
            case "狂风大作" -> n.slot("amb_狂风大作",
                    "🌊 一个大浪兜头浇下来，你灌了满嘴咸水",
                    "🎣 缆绳被风扯得嗡嗡直响，你赶紧又打了两个结",
                    "💧 浪花泼上甲板，你脚下一滑，踉跄着扶住了船舷");
            case "阴雨" -> n.slot("amb_阴雨",
                    "🕊 一只落汤的海鸥歪歪扭扭落在船舷上，甩了甩湿毛",
                    "🌧 雨水在帆上汇成细流，倒是把帆洗干净了",
                    "🪼 一只半透明的水母贴着船侧慢悠悠漂过");
            case "多云" -> n.slot("amb_多云",
                    "🕊 一只海鸟落在桅杆顶上打盹，一动也不动",
                    "☀️ 云影里漏下一道光柱，照得海面明一块暗一块",
                    "🐟 一小群鱼跟着船尾游，攒成个小小的旋涡");
            default -> n.slot("amb_晴空万里",
                    "🐬 一群海豚跃出海面，追着船头撒欢",
                    "🕊 一只海鸟落在帆顶，侧着头打量你",
                    "🐟 一条飞鱼“嗖”地掠过甲板上空，银光一闪",
                    "🐢 一只海龟慢悠悠地贴着船底游过",
                    "🐳 远处腾起一柱水雾——是鲸鱼在喷水！");
        };
    }

    /** 海盗来袭：把叙事写进 seaLog+eventDetail，挂上 pendCombat 等玩家在「迎战/甩开/花钱消灾」之间选；
     *  实际扣钱/扣货在 sailDecision 里按选择结算，让玩家有真正的决策空间。 */
    private void pirateEvent(Player p, String clientId, Voyage v) {
        Narrator n = narrator(clientId);
        String approach = n.slot("pirApproach",
                "远方水线上缓缓冒出一叶黑帆",
                "桅顶的瞭望哨传来一声变了调的惊呼——“船！”",
                "一艘船影无声无息地贴了上来",
                "海雾里钻出一艘挂着破黑旗的快船",
                "海风忽然送来一股桐油味——不好，是船！");
        String board = n.slot("pirBoard",
                "几只布满刺青的手臂攀上船舷，刀光一闪，一群海盗齐刷刷跳上甲板",
                "铁钩一扬，绳梯搭上船舷，灯笼的火光照出一张张贪婪的脸",
                "他们砸开舱门把货舱翻了个底朝天，又朝你伸出手",
                "领头的头目脚下踩着你的箱子，手里掂着匕首，意思不言自明",
                "刀把子在船舷上笃笃敲了两下，要钱还是要命，就等你一句话");

        List<String> lines = new ArrayList<>();
        lines.add("🏴【海盗】" + approach + "。" + board + "！");
        lines.add("⚔️ 怎么办？—— 迎战 / 甩开 / 花钱消灾（5 金币）");
        addAllLog(v, lines);
        v.eventDetail = List.copyOf(lines);
        v.pendCombat = true;
    }

    private void bottleEvent(Player p, String clientId, Voyage v) {
        Narrator n = narrator(clientId);
        String found = n.slot("botFound",
                "你在浪花里捞起一只鼓鼓的漂流瓶",
                "一只半埋在浮木下的漂流瓶露出圆溜溜的瓶口",
                "船头“咚”地一声，撞上一只封得严严实实的漂流瓶",
                "一堆浮碎里有个玻璃瓶晃晃悠悠地碰上了船底",
                "一只缠着海草的漂流瓶在浪尖一沉一浮，被你眼疾手快抄了起来");

        List<String> lines = new ArrayList<>();
        int kind = (int) (Math.random() * 100);
        if (kind < 35) {
            int gain = 5 + (int) (Math.random() * 26);          // 5~30 金币
            p.gold += gain;
            String content = n.slot("botCoin",
                    "撬开瓶口的蜡封，骨碌碌滚出一把亮闪闪的金币",
                    "倒出来——竟是一小袋沉甸甸的金币",
                    "摇了摇，瓶里哗啦作响，撞开一瞧，满满半瓶全是钱");
            lines.add("💰【漂流瓶】" + found + "，" + content + "！");
            lines.add("✨ 金币 +" + gain + "！");
            lines.add("（内心OS：*" + n.slot("botCoinOs",
                    "发了发了！！这运气，昨晚是不是烧了高香？！",
                    "哪位货主这么大方，把财宝直接往海里送？多谢多谢！",
                    "我摸着热乎乎的金币，笑得像个偷到松果的松鼠。",
                    "赶紧四下张望确认没人眼红，才美滋滋把钱袋塞进怀里。",
                    "海神总算开了眼！今天这几海里，值了！"
            ) + "*）");
        } else if (kind < 60) {
            int gain = 8 + (int) (Math.random() * 18);          // 8~25 金币
            p.gold += gain;
            String content = n.slot("botMap",
                    "展开一看，竟是一张画着大红叉的藏宝图",
                    "纸卷里卷着一张泛黄海图，某处标了个醒目的红叉",
                    "是一张标了沉船位置的海图，看水色方位，离这儿不远");
            lines.add("🗺【漂流瓶】" + found + "。" + content + "！你按图摸索着，竟真捞起一小箱陈年银币！");
            lines.add("✨ 金币 +" + gain + "！（沉船宝藏）");
            lines.add("（内心OS：*" + n.slot("botMapOs",
                    "祖坟冒青烟了这是！今晚靠岸必须开瓶好酒庆功！",
                    "嘿嘿，这可比没日没夜地跑商来钱快多了……",
                    "我把海图小心翼翼地贴胸放着：这要是顺，后半程本钱都有了！",
                    "难怪老船长说海上遍地是宝，全看你会不会捡！"
            ) + "*）");
        } else if (kind < 82) {
            int used = p.cargo.values().stream().mapToInt(Integer::intValue).sum();
            if (used < p.cargoCap) {
                String item = GOODS.get((int) (Math.random() * GOODS.size())).id();
                p.cargo.merge(item, 1, Integer::sum);
                String content = n.slot("botCargo",
                        "油布里裹着一份上等" + goodName(item) + "，光闻味儿就知道是稀罕货",
                        "是一只封蜡完好的小罐，撬开竟装着上乘" + goodName(item),
                        "布里包着的" + goodName(item) + "连包装都完好，看着就值钱");
                lines.add("📦【漂流瓶】" + found + "，" + content + "！");
                lines.add("✨ 白捡 " + goodName(item) + " ×1（免费入舱）！");
                lines.add("（内心OS：*" + n.slot("botCargoOs",
                        "不用花一个子儿就到手的货，这可是开门红！",
                        "好家伙，光这一件就够我这趟回本了！",
                        "我掂了掂分量，乐得直搓手：天上掉的馅饼，不吃白不吃！",
                        "这瓶子简直财神爷开的——管它装什么我都爱！"
                ) + "*）");
            } else {
                p.gold += 5;
                String content = n.slot("botRum",
                        "里面是半瓶朗姆酒，你掀开瓶口就灌了一大口",
                        "敲开瓶塞，一股甜酒的香气直往鼻子里钻，你灌了满满一大口",
                        "是陈年的朗姆，一口下去，暖意从嗓子一路烧到心口");
                lines.add("🍾【漂流瓶】" + found + "，" + content + "。");
                lines.add("✨（舱位已满，贪杯也算收获了快乐……和 5 枚金币）");
                lines.add("（内心OS：*" + n.slot("botRumOs",
                        "在海上能喝上这一口热酒，这日子值了！",
                        "我不舍得一口喝完，又舍不得放手——干脆揣怀里暖着，慢慢品。",
                        "酒壮怂人胆，看这海，竟也有三分可爱了！"
                ) + "*）");
            }
        } else {
            String msg = n.slot("botLetter",
                    "“船靠岸的那一天，我在老码头的桂花树下等你。回来就好。”",
                    "“离别的酒我喝了三碗。回来的路上，记得给我带一枝那边的野花。”",
                    "“娘腌的咸菜还给你留着呢。人壮实了，就早点回家。”",
                    "“院里那棵石榴又红了，今年结得特别多。”",
                    "“你若能回来，海风都会替我说想你。”");
            lines.add("💌【漂流瓶】" + found + "，展开信纸：" + msg);
            lines.add("（内心OS：*" + n.slot("botLetterOs",
                    "鼻子一酸，这茫茫大海上，原来还有人惦记着我。我把信小心翼翼地藏进怀里。",
                    "眼眶热了热，我别过脸去假装看海，趁没人注意，把信叠好收进胸口。",
                    "看罢良久无言，终于还是把信收好，长长地呼出一口气。",
                    "海上风再大，也吹不散这一纸牵挂。我把信贴在胸口，忽然觉得船都有了分量。",
                    "盯着那几行字看了好久，我突然特别想靠岸——真的，想回家了。"
            ) + "*）");
        }
        addAllLog(v, lines);
        v.eventDetail = List.copyOf(lines);
    }

    private void lightningEvent(Player p, String clientId, Voyage v) {
        Narrator n = narrator(clientId);
        String strike = n.slot("lgtStrike",
                "一道惨白的闪电直直劈中桅杆，火星兜头炸开",
                "轰隆一声，一道电蛇顺着桅杆盘旋而下，木屑横飞",
                "刺目的白光一闪，雷不偏不倚，正落在帆尖上",
                "闪电像个发了怒的巨人，一拳凿在船顶上，火光四溅",
                "雷声和火光同时炸亮——桅顶已经烧了起来");

        List<String> lines = new ArrayList<>();
        int total = p.cargo.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) {
            lines.add("⚡【雷击】" + strike + "！");
            lines.add("　好在船舱空空，火苗没烧到货，只把" + n.slot("lgtEmptyAft",
                    "半面帆燎得焦黑",
                    "船舷熏出一片黑印",
                    "一根缆绳烧断了半截") + "。");
            lines.add("（内心OS：*" + n.slot("lgtEmptyOs",
                    "好险……！命还在，比啥都强。回头得给妈祖上炷香。",
                    "空船也有空船的好——烧了不心疼，货单干干净净！",
                    "后背一层冷汗。这要是装了货，可就全交代了。",
                    "我望着那片焦痕直叹气：这意头……怕是要顺一阵风再转运。"
            ) + "*）");
            addAllLog(v, lines);
            v.eventDetail = List.copyOf(lines);
            return;
        }

        int pct = 1 + (int) (Math.random() * 10);                // 最多减少 10%
        int lossUnits = Math.max(1, (int) Math.ceil(total * pct / 100.0));
        List<String> lostDesc = new ArrayList<>();
        // 用 keySet 快照遍历：下面会 remove/put，直接迭代 entrySet 会抛 ConcurrentModificationException
        for (String key : List.copyOf(p.cargo.keySet())) {
            if (lossUnits <= 0) break;
            int have = p.cargo.getOrDefault(key, 0);
            int take = Math.min(have, lossUnits);
            if (take <= 0) continue;
            int remain = have - take;
            if (remain <= 0) p.cargo.remove(key); else p.cargo.put(key, remain);
            lostDesc.add(goodName(key) + "×" + take);
            lossUnits -= take;
        }
        String aft = n.slot("lgtAft",
                "你连滚带爬地扑上去压火，慌乱间几箱货滚落船舷，砸进浪里",
                "火苗蹿起又被湿麻布扑灭，可回过神，舱里的货已经少了一角",
                "烟呛得你睁不开眼，等分清水火，甲板上已经散落着好几只空箱",
                "你嘶吼着往下泼水，总算压住了火，可那些装货的木箱，还是被卷走了两只");
        lines.add("⚡【雷击】" + strike + "！" + aft + "……");
        lines.add("📦 货物损失 " + String.join("、", lostDesc) + "（约 -" + pct + "%）！");
        lines.add("（内心OS：*" + n.slot("lgtOs",
                "这一船的货，眼看着就白拉了一大截……我的心在滴血啊！",
                "啊啊啊！攒了这么久的货！老天爷你就不能挑别人家的船劈吗？！",
                "行吧……能活着就是万幸。可这损失，光想想就胸口疼。",
                "我趴在甲板上把剩货扒拉了一遍又一遍，怎么也不肯相信少了一截。",
                "抹把脸站起来：哭也没用。这点损失，靠岸一票买卖就能补回来！",
                "祸不单行哪……我抬头望天，黑云里又滚过来一声闷雷。",
                "手还在抖，腿也发软。可船还得开、钱还得赚，我扶着船舷，狠狠咽了口唾沫。"
        ) + "*）");
        addAllLog(v, lines);
        v.eventDetail = List.copyOf(lines);
    }

    private String goodName(String id) {
        Good g = goodMap.get(id);
        return g != null ? g.name() : id;
    }

    /** 检测前方是否进入某其它港口的「途经范围」，若有则将 offeredId 置为该港待玩家决策 */
    private void detectWaypoint(Voyage v, double prevLat, double prevLng) {
        Port best = null;
        double bestD = PASSING_RADIUS_KM;
        for (Port po : PORTS) {
            if (po.id().equals(v.fromId) || po.id().equals(v.destId) || v.passed.contains(po.id())) continue;
            double d = havKm(v.lat, v.lng, po.lat(), po.lng());
            if (d <= bestD) {
                // 必须比上一次点击更接近该港（保证是“前方接近中”，不会回头发作过的港）
                double prev = havKm(prevLat, prevLng, po.lat(), po.lng());
                if (d < prev - 0.5) {
                    best = po;
                    bestD = d;
                }
            }
        }
        if (best != null) v.offeredId = best.id();
    }

    // ---------- 持久化辅助 ----------

    private PlayerEntity toEntity(Player p, String clientId) {
        PlayerEntity e = new PlayerEntity();
        e.clientId = clientId;
        e.name = p.name;
        e.gold = p.gold;
        e.port = p.port;
        e.cargoCap = p.cargoCap;
        e.cargo = new HashMap<>(p.cargo);
        e.traveling = p.voyage != null;
        e.travelingTo = (p.voyage != null) ? p.voyage.destId : null;
        e.arriveAt = 0;
        return e;
    }

    /** 有则更新、无则插入（MyBatis 无 JPA 的 save 语义，这里显式判断） */
    private void persist(Player p, String clientId) {
        PlayerEntity e = toEntity(p, clientId);
        if (repo.findById(clientId) == null) {
            repo.insert(e);
        } else {
            repo.update(e);
        }
    }

    // ---------- 推送 ----------

    private void sendState(String clientId) {
        Player p = players.get(clientId);
        if (p == null) return;
        Map<String, Object> state = new LinkedHashMap<>();
        Map<String, Object> you = new LinkedHashMap<>();
        you.put("name", p.name);
        you.put("gold", p.gold);
        you.put("port", p.port);
        you.put("cargoCap", p.cargoCap);
        you.put("cargo", p.cargo);
        you.put("traveling", p.voyage != null);
        state.put("you", you);

        // 只下发前端需要的字段（buy/sell/trend），不暴露 base/drift/stock 等内部量
        Map<String, Object> mk = new LinkedHashMap<>();
        for (Map.Entry<String, MarketItem> en : market.getOrDefault(p.port, Map.of()).entrySet()) {
            MarketItem it = en.getValue();
            mk.put(en.getKey(), Map.of("buy", it.buy, "sell", it.sell, "trend", it.trend));
        }
        state.put("market", mk);

        // 港口列表：含所属海域 / 真实坐标 / 距当前港距离（供地图页显示）
        Port cur = portMap.get(p.port);
        state.put("ports", PORTS.stream().map(po -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", po.id());
            m.put("name", po.name());
            m.put("region", po.regionId());
            m.put("x", po.lng());
            m.put("y", po.lat());
            m.put("distanceKm", (int) Math.round(havKm(cur.lat(), cur.lng(), po.lat(), po.lng())));
            return m;
        }).toList());

        // 一级地图：海域 → 二级：港口
        state.put("regions", REGIONS.stream()
                .map(r -> Map.of("id", r.id(), "name", r.name()))
                .toList());

        state.put("goods", GOODS.stream()
                .map(g -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", g.id());
                    m.put("name", g.name());
                    m.put("homeRegion", GOOD_HOME_REGION.getOrDefault(g.id(), ""));
                    return m;
                })
                .toList());

        // 航行状态
        if (p.voyage != null) {
            Voyage v = p.voyage;
            Map<String, Object> vv = new LinkedHashMap<>();
            vv.put("fromId", v.fromId);
            vv.put("fromName", portName(v.fromId));
            vv.put("destId", v.destId);
            vv.put("destName", portName(v.destId));
            vv.put("totalKm", (int) Math.round(v.totalKm));
            vv.put("traveledKm", (int) Math.round(v.traveledKm));
            vv.put("remainingKm", Math.max(0, (int) Math.round(v.totalKm - v.traveledKm)));
            vv.put("lat", v.lat);
            vv.put("lng", v.lng);
            vv.put("sea", v.seaName);
            vv.put("offered", v.offeredId == null
                    ? null
                    : Map.of("id", v.offeredId, "name", portName(v.offeredId)));
            vv.put("departed", v.departed);
            vv.put("weather", p.weather);
            vv.put("weatherWarned", v.weatherWarned);
            vv.put("mood", v.mood);
            vv.put("event", v.event);
            vv.put("eventDetail", v.eventDetail == null ? List.of() : List.copyOf(v.eventDetail));
            vv.put("pendCombat", v.pendCombat);
            vv.put("seaLog", List.copyOf(v.seaLog));
            vv.put("newLines", List.copyOf(v.newLines));
            state.put("voyage", vv);
        } else {
            state.put("voyage", null);
        }

        tmpl.convertAndSend("/topic/player/" + clientId + "/state", state);
        if (p.voyage != null) p.voyage.newLines.clear();   // 本次动作的新增行已下发，立刻清空避免泄漏到下一次推送
    }

    /** 把最新行情推送给某个港口的所有在线玩家（玩家买卖会影响同港其他人） */
    private void sendStateToAllAt(String port) {
        for (Map.Entry<String, Player> en : players.entrySet()) {
            if (port.equals(en.getValue().port)) sendState(en.getKey());
        }
    }

    private void sendMsg(String clientId, String text) {
        tmpl.convertAndSend("/topic/player/" + clientId + "/msg", Map.of("text", text));
    }

    private void broadcast(String text) {
        tmpl.convertAndSend("/topic/log", Map.of("text", text));
    }

    private String portName(String id) {
        Port p = portMap.get(id);
        return p != null ? p.name() : id;
    }

    /** 追加日志行：同时写进 seaLog（全量航海日志）和 newLines（本次动作新增行，前端事件卡直接展示） */
    private static void addLog(Voyage v, String... lines) {
        for (String line : lines) { v.seaLog.add(line); v.newLines.add(line); }
    }

    private static void addAllLog(Voyage v, List<String> lines) {
        v.seaLog.addAll(lines);
        v.newLines.addAll(lines);
    }

    private static void trim(List<String> log) {
        while (log.size() > 60) log.remove(0);
    }

    /** 行情条目：价格由「基准价 + 时间波动 + 玩家供需」三者共同决定 */
    public static class MarketItem {
        public int base;        // 货物基准价
        public double drift;    // 时间因素：[-range, +range]
        public double stock;    // 供需：>0 积压(跌价)，<0 被抢购(涨价)
        public int buy;
        public int sell;
        public int trend;       // 相对基准价的百分比，如 +23 / -15

        public MarketItem(int base) {
            this.base = base;
            this.drift = 0;
            this.stock = 0;
            this.buy = base;
            this.sell = (int) Math.round(base * 0.9);
            this.trend = 0;
        }
    }
}