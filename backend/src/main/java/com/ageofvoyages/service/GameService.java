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
import com.ageofvoyages.repository.PlayerMapper;
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
    /** 每次点击「航行」前进的公里数 */
    private static final double KM_PER_CLICK = 10;
    /** 距当前航位多近的其它港口会被视为「途经地」（可进港 / 继续） */
    private static final double PASSING_RADIUS_KM = 220;
    /** 距任何港口超过该公里数才算“海中间”——海盗、雷击等坏事件只发生在真正的大洋上 */
    private static final double DEEP_SEA_KM = 150;
    /** 全程超过该公里数的长途（跨海/跨洲）航线，才可能遭遇海盗、雷击等坏事件 */
    private static final double LONG_HAUL_KM = 1500;
    /** 地球半径（公里），用于大圆距离 / 航线插值 */
    private static final double EARTH_R = 6371.0;

    private final SimpMessagingTemplate tmpl;
    private final PlayerMapper repo;

    // ---------- 世界地图（一级：海域 / 二级：港口，带真实经纬度） ----------
    private record Region(String id, String name) {}

    private final List<Region> REGIONS = List.of(
            new Region("north-sea", "西北欧 · 北海"),
            new Region("mediterranean", "地中海"),
            new Region("indian", "印度洋 · 南洋"),
            new Region("asia", "亚洲 · 远东")
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

    /** 行情：portId -> (goodId -> MarketItem) */
    private final Map<String, Map<String, MarketItem>> market = new ConcurrentHashMap<>();
    /** 在线玩家（实时状态，内存）：clientId -> Player */
    private final Map<String, Player> players = new ConcurrentHashMap<>();
    /** 每位玩家的组合式叙事器：保证同一玩家反复遭遇同一事件时，描述与内心OS尽量不重样 */
    private final Map<String, Narrator> narrators = new ConcurrentHashMap<>();

    public GameService(SimpMessagingTemplate tmpl, PlayerMapper repo) {
        this.tmpl = tmpl;
        this.repo = repo;
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
                recalc(it);
            }
        }
        for (String cid : players.keySet()) sendState(cid);
    }

    /** 最终价 = 基准价 ×(1+时间波动) ×(1+供需影响)；tanh 保证平滑饱和 */
    private void recalc(MarketItem it) {
        double eff = it.base * (1 + it.drift);
        double mult = 1 + MAX_DEV * Math.tanh(-it.stock / SCALE); // stock 为负(被买走)→涨价
        it.buy = Math.max(1, (int) Math.round(eff * mult));
        it.sell = Math.max(1, (int) Math.round(it.buy * 0.9));
        it.trend = (int) Math.round((it.buy / (double) it.base - 1) * 100);
    }

    // ---------- 指令 ----------

    public void login(LoginRequest r) {
        Player p = players.computeIfAbsent(r.clientId(), k -> new Player());
        // 从 MySQL 恢复已保存的进度
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
            p.voyage = null;
        }
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
        int clicks = (int) Math.ceil(total / KM_PER_CLICK);

        Voyage v = new Voyage();
        v.fromId = p.port;
        v.destId = dest.id();
        v.totalKm = total;
        v.traveledKm = 0;
        v.lat = from.lat();
        v.lng = from.lng();
        v.departed = false;
        v.seaLog.add("🗺 航线已规划：由 " + from.name() + " 前往 " + dest.name()
                + "，全程 " + (int) Math.round(total) + " 公里，约需 " + clicks + " 次航行。点击「出发」启程！");
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
            double step = Math.min(KM_PER_CLICK, v.traveledKm);
            double brng = bearingDeg(v.lat, v.lng, from.lat(), from.lng());
            double[] np = destPoint(v.lat, v.lng, brng, step);
            v.lat = np[0];
            v.lng = np[1];
            v.traveledKm = Math.max(0, v.traveledKm - step);

            // 后退时离某个「已略过」的港越来越远，就把它重新解锁，之后再次靠近可重新选择
            for (String pid : List.copyOf(v.passed)) {
                Port po = portMap.get(pid);
                if (po == null) continue;
                if (havKm(v.lat, v.lng, po.lat(), po.lng()) > PASSING_RADIUS_KM) v.passed.remove(pid);
            }

            if (v.traveledKm <= 1e-9) {
                v.seaLog.add("🔄 你掉头返航，重新靠上 " + from.name() + " 的码头。");
                arriveAt(p, r.clientId(), v, from.id(), "返航回到出发港 " + from.name() + "（全程未走完）");
                return;
            }
            v.seaLog.add("⏪ 后退 10 公里，已航行 " + (int) Math.round(v.traveledKm) + "/" + (int) Math.round(v.totalKm) + " 公里");
            trim(v.seaLog);
            detectWaypoint(v, prevLat, prevLng);
            sendState(r.clientId());
            return;
        }

        // 前进（首次点击确认「出发」）
        boolean justDeparted = !v.departed;
        v.departed = true;
        double step = Math.min(KM_PER_CLICK, v.totalKm - v.traveledKm);
        double brng = bearingDeg(v.lat, v.lng, dest.lat(), dest.lng());
        double[] np = destPoint(v.lat, v.lng, brng, step);
        v.lat = np[0];
        v.lng = np[1];
        v.traveledKm += step;

        if (justDeparted) v.seaLog.add("⛵ 拔锚启航！船头劈开浪花，驶向 " + dest.name());

        if (v.traveledKm >= v.totalKm - 1e-9) {
            v.seaLog.add("🏝 抵达 " + dest.name() + "港！航程结束。");
            arriveAt(p, r.clientId(), v, dest.id(), "🏝 历经 " + (int) Math.round(v.traveledKm) + " 公里航行，抵达 " + dest.name() + "港");
            broadcast("🏝 船长 [" + p.name + "] 历经" + (int) Math.round(v.traveledKm) + "公里航行，抵达 " + dest.name() + "港");
            return;
        }

        // 天气是慢慢变的（沿用上一次航行时就开始的天气），偶尔才渐变一次，不会每次点击都变天
        String oldWeather = p.weather;
        String weather = driftWeather(p);
        if (oldWeather != null && !oldWeather.equals(weather)) {
            v.seaLog.add(weatherShift(r.clientId(), oldWeather, weather));
        }
        v.seaLog.add(seaReport(v, weather));                          // 数字进度（稳定，供 UI/测试解析）
        v.seaLog.add(weatherMood(r.clientId(), weather));             // 天气连着心情：好天开心，坏天忐忑
        if (Math.random() < 0.30) v.seaLog.add(ambientEvent(r.clientId(), weather)); // 海上小彩蛋
        rollSeaEvents(p, r.clientId(), v, weather);                   // 大事件：漂流瓶(远近都有) / 海盗·雷击(只在大洋长途)

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
        if ("return".equals(r.choice())) {
            boolean preDepart = !v.departed;
            String backPort = v.fromId;
            v.seaLog.add("↩ " + (preDepart ? "你收起航海图，放弃了这条航线。" : "你调转船头，掉头回航。"));
            arriveAt(p, r.clientId(), v, backPort,
                    preDepart ? "已取消航线，留在 " + portName(backPort) + "港" : "返回出发港 " + portName(backPort));
            broadcast("↩ 船长 [" + p.name + "] " + (preDepart ? "取消了" + portName(v.destId) + "的航线" : "中途返回了 " + portName(backPort) + "港"));
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
            v.seaLog.add("🚢 略过 " + portName(pid) + "，继续向目的地航行");
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

        recalc(item);
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

    /** 随机今天的天气（事件叙述按天气联动） */
    private static String randomWeather() {
        String[] weather = {"晴空万里", "多云", "阴雨", "下雷雨", "狂风大作"};
        return weather[(int) (Math.random() * weather.length)];
    }

    /** 每次点击的海况报告：只报数字进度与天气/海面等「事实」，供 UI 与测试稳定解析（叙事由 weatherMood 等承担） */
    private String seaReport(Voyage v, String weather) {
        String[] sea = {"风平浪静", "微波荡漾", "小浪翻涌", "大浪起伏", "巨浪滔天"};
        String s = sea[(int) (Math.random() * sea.length)];
        boolean thunder = "下雷雨".equals(weather);
        int remain = Math.max(0, (int) Math.ceil((v.totalKm - v.traveledKm) / KM_PER_CLICK));
        return "🌊 已航行 " + (int) Math.round(v.traveledKm) + " / " + (int) Math.round(v.totalKm)
                + " 公里（还需 " + remain + " 次） ｜ 天气：" + weather + " ｜ 海面：" + s
                + " ｜ 雷雨：" + (thunder ? "🌩 电闪雷鸣" : "无");
    }

    /** 天气沿用上一次点击的，偶尔（约 7%）才渐变一次 —— 不会每次点击都换一种天 */
    private String driftWeather(Player p) {
        if (p.weather == null) return p.weather = randomWeather();
        if (Math.random() < 0.07) {
            List<String> pool = new ArrayList<>(List.of("晴空万里", "多云", "阴雨", "下雷雨", "狂风大作"));
            pool.remove(p.weather);
            p.weather = pool.get((int) (Math.random() * pool.size()));
        }
        return p.weather;
    }

    /** 当前航位离最近港口多少公里（判断是否已到“海中间”） */
    private double minPortKm(Voyage v) {
        double best = Double.MAX_VALUE;
        for (Port po : PORTS) {
            best = Math.min(best, havKm(v.lat, v.lng, po.lat(), po.lng()));
        }
        return best;
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

    /** 每个前进航次掷随机遭遇：
     *  漂流瓶：近海、远海都可能碰到（正儿八经的小惊喜）；
     *  海盗 / 雷击：只在真正的大洋深处、且是长途（跨海/跨洲）航线里才会发生——
     *       刚出港的近海绝不会遇到，且每 300 公里以内最多打劫一次。 */
    private void rollSeaEvents(Player p, String clientId, Voyage v, String weather) {
        // 漂流瓶：顺流漂来的惊喜，不分远近
        if (Math.random() < 0.08) bottleEvent(p, clientId, v);

        boolean deepSea = minPortKm(v) >= DEEP_SEA_KM;           // 海中间（远离一切港口）
        boolean longHaul = v.totalKm >= LONG_HAUL_KM;             // 长途航线（跨海/跨洲）

        // 海盗：大洋深处的老客户，每 300 公里最多一笔
        if (deepSea && longHaul && v.traveledKm - v.lastPirateKm >= 300 && Math.random() < 0.15) {
            pirateEvent(p, clientId, v);
            v.lastPirateKm = v.traveledKm;
        }

        // 雷击：雷雨天 + 大洋深处才可能劈到船，且与上一次拉开距离
        if ("下雷雨".equals(weather) && deepSea && longHaul
                && v.traveledKm - v.lastLightningKm >= 200 && Math.random() < 0.12) {
            lightningEvent(p, clientId, v);
            v.lastLightningKm = v.traveledKm;
        }
    }

    /** 天气连着心情：晴好多云则心情大好、哼两句船歌；坏天气则心里打鼓，怕有什么不测 */
    private String weatherMood(String clientId, String weather) {
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

    private void pirateEvent(Player p, String clientId, Voyage v) {
        Narrator n = narrator(clientId);
        int loss = 1 + (int) (Math.random() * 10);              // 最多 10 金币
        int lost = Math.min(loss, p.gold);
        p.gold -= lost;
        String approach = n.slot("pirApproach",
                "远方水线上缓缓冒出一叶黑帆",
                "桅顶的瞭望哨传来一声变了调的惊呼——“船！”",
                "一艘船影无声无息地贴了上来",
                "海雾里钻出一艘挂着破黑旗的快船",
                "海风忽然送来一股桐油味——不好，是船！");
        if (lost > 0) {
            String board = n.slot("pirBoard",
                    "几只布满刺青的手臂攀上船舷，刀光一闪，一群海盗齐刷刷跳上甲板",
                    "铁钩一扬，绳梯搭上船舷，灯笼的火光照出一张张贪婪的脸",
                    "他们砸开舱门把货舱翻了个底朝天，又朝你伸出手",
                    "领头的头目脚下踩着你的箱子，手里掂着匕首，意思不言自明",
                    "刀把子在船舷上笃笃敲了两下，要钱还是要命，就等你一句话");
            v.seaLog.add("🏴【海盗】" + approach + "。" + board + "！");
            v.seaLog.add("💰 被抢走 " + lost + " 金币！");
            v.seaLog.add("（内心OS：*" + n.slot("pirOs",
                    "这帮天杀的！等我攒够了钱，雇一队火枪手把他们全扔海里喂鱼！",
                    "气炸了！这笔账我记下了，改日连本带利讨回来！",
                    "呜……存了这么久的钱，这一下全没了，心都在滴血！",
                    "人在舱檐下，不得不低头。可这口气，我是真咽不下去……",
                    "好汉不吃眼前亏，先保住命要紧。可这些钱……啊啊啊！",
                    "我盯着他们离去的船影，把每个刺青都刻进了脑子里。",
                    "破财消灾，破财消灾……我数着空荡荡的钱袋，拼命劝自己冷静。",
                    "穷家富路，这下真穷到家了。罢了，玩命把这票货卖个好价再赚回来！"
            ) + "*）");
        } else {
            v.seaLog.add("🏴【海盗】" + approach + "。" + n.slot("pirEmpty",
                    "海盗们翻遍全船舱也没搜出一个子儿，照着船舷啐了一口，骂骂咧咧地扬帆去了",
                    "他们撬开你身上唯一的口袋，连个铜板都没摸到，扫兴地撤了",
                    "领头的掂了掂空钱袋，嫌晦气，一脚踢回你怀里，带着人撤了") + "。");
            v.seaLog.add("（内心OS：*" + n.slot("pirEmptyOs",
                    "穷得叮当响倒还躲过一劫……怎么还有点小得意？",
                    "哈哈，光脚的不怕穿鞋的，钱袋空空就是我的护身符！",
                    "等老子赚了钱，第一件事就是把你们这伙都赎了当苦力，看还敢劫穷船！"
            ) + "*）");
        }
    }

    private void bottleEvent(Player p, String clientId, Voyage v) {
        Narrator n = narrator(clientId);
        String found = n.slot("botFound",
                "你在浪花里捞起一只鼓鼓的漂流瓶",
                "一只半埋在浮木下的漂流瓶露出圆溜溜的瓶口",
                "船头“咚”地一声，撞上一只封得严严实实的漂流瓶",
                "一堆浮碎里有个玻璃瓶晃晃悠悠地碰上了船底",
                "一只缠着海草的漂流瓶在浪尖一沉一浮，被你眼疾手快抄了起来");

        int kind = (int) (Math.random() * 100);
        if (kind < 35) {
            int gain = 5 + (int) (Math.random() * 26);          // 5~30 金币
            p.gold += gain;
            String content = n.slot("botCoin",
                    "撬开瓶口的蜡封，骨碌碌滚出一把亮闪闪的金币",
                    "倒出来——竟是一小袋沉甸甸的金币",
                    "摇了摇，瓶里哗啦作响，撞开一瞧，满满半瓶全是钱");
            v.seaLog.add("💰【漂流瓶】" + found + "，" + content + "！");
            v.seaLog.add("✨ 金币 +" + gain + "！");
            v.seaLog.add("（内心OS：*" + n.slot("botCoinOs",
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
            v.seaLog.add("🗺【漂流瓶】" + found + "。" + content + "！你按图摸索着，竟真捞起一小箱陈年银币！");
            v.seaLog.add("✨ 金币 +" + gain + "！（沉船宝藏）");
            v.seaLog.add("（内心OS：*" + n.slot("botMapOs",
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
                v.seaLog.add("📦【漂流瓶】" + found + "，" + content + "！");
                v.seaLog.add("✨ 白捡 " + goodName(item) + " ×1（免费入舱）！");
                v.seaLog.add("（内心OS：*" + n.slot("botCargoOs",
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
                v.seaLog.add("🍾【漂流瓶】" + found + "，" + content + "。");
                v.seaLog.add("✨（舱位已满，贪杯也算收获了快乐……和 5 枚金币）");
                v.seaLog.add("（内心OS：*" + n.slot("botRumOs",
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
            v.seaLog.add("💌【漂流瓶】" + found + "，展开信纸：" + msg);
            v.seaLog.add("（内心OS：*" + n.slot("botLetterOs",
                    "鼻子一酸，这茫茫大海上，原来还有人惦记着我。我把信小心翼翼地藏进怀里。",
                    "眼眶热了热，我别过脸去假装看海，趁没人注意，把信叠好收进胸口。",
                    "看罢良久无言，终于还是把信收好，长长地呼出一口气。",
                    "海上风再大，也吹不散这一纸牵挂。我把信贴在胸口，忽然觉得船都有了分量。",
                    "盯着那几行字看了好久，我突然特别想靠岸——真的，想回家了。"
            ) + "*）");
        }
    }

    private void lightningEvent(Player p, String clientId, Voyage v) {
        Narrator n = narrator(clientId);
        String strike = n.slot("lgtStrike",
                "一道惨白的闪电直直劈中桅杆，火星兜头炸开",
                "轰隆一声，一道电蛇顺着桅杆盘旋而下，木屑横飞",
                "刺目的白光一闪，雷不偏不倚，正落在帆尖上",
                "闪电像个发了怒的巨人，一拳凿在船顶上，火光四溅",
                "雷声和火光同时炸亮——桅顶已经烧了起来");

        int total = p.cargo.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) {
            v.seaLog.add("⚡【雷击】" + strike + "！");
            v.seaLog.add("　好在船舱空空，火苗没烧到货，只把" + n.slot("lgtEmptyAft",
                    "半面帆燎得焦黑",
                    "船舷熏出一片黑印",
                    "一根缆绳烧断了半截") + "。");
            v.seaLog.add("（内心OS：*" + n.slot("lgtEmptyOs",
                    "好险……！命还在，比啥都强。回头得给妈祖上炷香。",
                    "空船也有空船的好——烧了不心疼，货单干干净净！",
                    "后背一层冷汗。这要是装了货，可就全交代了。",
                    "我望着那片焦痕直叹气：这意头……怕是要顺一阵风再转运。"
            ) + "*）");
            return;
        }

        int pct = 1 + (int) (Math.random() * 10);                // 最多减少 10%
        int lossUnits = Math.max(1, (int) Math.ceil(total * pct / 100.0));
        List<String> lostDesc = new ArrayList<>();
        for (Map.Entry<String, Integer> e : p.cargo.entrySet()) {
            if (lossUnits <= 0) break;
            int take = Math.min(e.getValue(), lossUnits);
            if (take <= 0) continue;
            p.cargo.merge(e.getKey(), -take, Integer::sum);
            if (p.cargo.get(e.getKey()) <= 0) p.cargo.remove(e.getKey());
            lostDesc.add(goodName(e.getKey()) + "×" + take);
            lossUnits -= take;
        }
        String aft = n.slot("lgtAft",
                "你连滚带爬地扑上去压火，慌乱间几箱货滚落船舷，砸进浪里",
                "火苗蹿起又被湿麻布扑灭，可回过神，舱里的货已经少了一角",
                "烟呛得你睁不开眼，等分清水火，甲板上已经散落着好几只空箱",
                "你嘶吼着往下泼水，总算压住了火，可那些装货的木箱，还是被卷走了两只");
        v.seaLog.add("⚡【雷击】" + strike + "！" + aft + "……");
        v.seaLog.add("📦 货物损失 " + String.join("、", lostDesc) + "（约 -" + pct + "%）！");
        v.seaLog.add("（内心OS：*" + n.slot("lgtOs",
                "这一船的货，眼看着就白拉了一大截……我的心在滴血啊！",
                "啊啊啊！攒了这么久的货！老天爷你就不能挑别人家的船劈吗？！",
                "行吧……能活着就是万幸。可这损失，光想想就胸口疼。",
                "我趴在甲板上把剩货扒拉了一遍又一遍，怎么也不肯相信少了一截。",
                "抹把脸站起来：哭也没用。这点损失，靠岸一票买卖就能补回来！",
                "祸不单行哪……我抬头望天，黑云里又滚过来一声闷雷。",
                "手还在抖，腿也发软。可船还得开、钱还得赚，我扶着船舷，狠狠咽了口唾沫。"
        ) + "*）");
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
                .map(g -> Map.of("id", g.id(), "name", g.name()))
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
            vv.put("lat", v.lat);
            vv.put("lng", v.lng);
            vv.put("clicks", (int) Math.ceil(v.totalKm / KM_PER_CLICK));
            vv.put("clicked", (int) Math.ceil(Math.min(v.traveledKm, v.totalKm) / KM_PER_CLICK));
            vv.put("offered", v.offeredId == null
                    ? null
                    : Map.of("id", v.offeredId, "name", portName(v.offeredId)));
            vv.put("departed", v.departed);
            vv.put("seaLog", List.copyOf(v.seaLog));
            state.put("voyage", vv);
        } else {
            state.put("voyage", null);
        }

        tmpl.convertAndSend("/topic/player/" + clientId + "/state", state);
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