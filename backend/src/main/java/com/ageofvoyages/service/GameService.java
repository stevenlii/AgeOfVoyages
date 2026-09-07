package com.ageofvoyages.service;

import com.ageofvoyages.dto.ChatRequest;
import com.ageofvoyages.dto.LoginRequest;
import com.ageofvoyages.dto.TradeRequest;
import com.ageofvoyages.dto.TravelRequest;
import com.ageofvoyages.model.Good;
import com.ageofvoyages.model.Player;
import com.ageofvoyages.model.PlayerEntity;
import com.ageofvoyages.model.Port;
import com.ageofvoyages.repository.PlayerMapper;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.*;
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

    private final SimpMessagingTemplate tmpl;
    private final PlayerMapper repo;

    /** 航行到达的定时调度（守护线程） */
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "travel-scheduler");
                t.setDaemon(true);
                return t;
            });

    // ---------- 静态配置（原型写死，后期进数据库） ----------
    private final List<Port> PORTS = List.of(
            new Port("london", "伦敦", 30),
            new Port("lisbon", "里斯本", 30),
            new Port("genoa", "热那亚", 60)
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

    public GameService(SimpMessagingTemplate tmpl, PlayerMapper repo) {
        this.tmpl = tmpl;
        this.repo = repo;
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
            p.traveling = e.traveling;
            p.travelingTo = e.travelingTo;
            p.arriveAt = e.arriveAt;
            // 重启恢复：若航行已到期，直接判定到达
            if (p.traveling && p.arriveAt > 0 && p.arriveAt <= System.currentTimeMillis()) {
                p.port = p.travelingTo;
                p.traveling = false;
                p.travelingTo = null;
            }
        }
        if (r.name() != null && !r.name().isBlank()) p.name = r.name();
        persist(p, r.clientId());
        sendState(r.clientId());
        broadcast("船长 [" + p.name + "] 登陆了 " + portName(p.port) + "港");
    }

    public void travel(TravelRequest r) {
        Player p = players.get(r.clientId());
        if (p == null) return;
        if (p.traveling) { sendMsg(r.clientId(), "正在航行中，无法操作"); return; }
        Port dest = portMap.get(r.to());
        if (dest == null || dest.id().equals(p.port)) { sendMsg(r.clientId(), "目标港口无效"); return; }

        p.traveling = true;
        p.travelingTo = r.to();
        p.arriveAt = System.currentTimeMillis() + dest.travelTime() * 1000L;
        persist(p, r.clientId());
        sendState(r.clientId());
        broadcast("⛵ 船长 [" + p.name + "] 从 " + portName(p.port) + " 启航，前往 " + dest.name() + "（约 " + dest.travelTime() + " 秒）");

        final String clientId = r.clientId();
        scheduler.schedule(() -> {
            p.port = r.to();
            p.traveling = false;
            p.travelingTo = null;
            persist(p, clientId);
            sendState(clientId);
            broadcast("🏝 船长 [" + p.name + "] 抵达 " + dest.name() + "港");
        }, dest.travelTime(), TimeUnit.SECONDS);
    }

    public void trade(TradeRequest r) {
        Player p = players.get(r.clientId());
        if (p == null) return;
        if (p.traveling) { sendMsg(r.clientId(), "航行中无法交易"); return; }
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

    // ---------- 持久化辅助 ----------

    private PlayerEntity toEntity(Player p, String clientId) {
        PlayerEntity e = new PlayerEntity();
        e.clientId = clientId;
        e.name = p.name;
        e.gold = p.gold;
        e.port = p.port;
        e.cargoCap = p.cargoCap;
        e.cargo = new HashMap<>(p.cargo);
        e.traveling = p.traveling;
        e.travelingTo = p.travelingTo;
        e.arriveAt = p.arriveAt;
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
        you.put("traveling", p.traveling
                ? Map.of("to", p.travelingTo, "arriveAt", p.arriveAt)
                : null);
        state.put("you", you);
        // 只下发前端需要的字段（buy/sell/trend），不暴露 base/drift/stock 等内部量
        Map<String, Object> mk = new LinkedHashMap<>();
        for (Map.Entry<String, MarketItem> en : market.getOrDefault(p.port, Map.of()).entrySet()) {
            MarketItem it = en.getValue();
            mk.put(en.getKey(), Map.of("buy", it.buy, "sell", it.sell, "trend", it.trend));
        }
        state.put("market", mk);
        state.put("ports", PORTS.stream()
                .map(po -> Map.of("id", po.id(), "name", po.name(), "travelTime", po.travelTime()))
                .toList());
        state.put("goods", GOODS.stream()
                .map(g -> Map.of("id", g.id(), "name", g.name()))
                .toList());
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
