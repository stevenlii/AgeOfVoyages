package com.ageofvoyages.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 手动航行状态：起点 → 终点，每次点击前进/后退 10 公里；途中经过其它地方时可选择进港或继续航行。 */
public class Voyage {
    /** 出发港 id */
    public String fromId;
    /** 目的地 id */
    public String destId;
    /** 是否已启航出发（false = 仅规划好航线，尚未出发，可以无损失取消） */
    public boolean departed = false;
    /** 全程公里数 */
    public double totalKm;
    /** 已航行公里数 */
    public double traveledKm;
    /** 当前纬度 / 经度（沿大圆航线每击推进） */
    public double lat;
    public double lng;
    /** 当前所在海域（如「印度洋 · 大洋深处」，由最近的港口归属到某个大洋；航行中界面常驻显示） */
    public String seaName = "";
    /** 当前「前方可进港」的途经地 id，null 表示无（需要玩家选择进港 / 继续） */
    public String offeredId;
    /** 航程逐步记录（每次点击的海况报告等），最多保留近 60 条（会被从开头裁剪，长度不再增长） */
    public List<String> seaLog = new ArrayList<>();
    /** 上一次动作追加的日志行：前端事件卡直接展示它，不受 seaLog 裁剪影响；由 sendState 发送后清空 */
    public List<String> newLines = new ArrayList<>();
    /** 已经「继续航行」略过的途经地 */
    public Set<String> passed = new HashSet<>();
    /** 最近一次遭遇海盗时的已航行公里数（保证每 1000 公里内最多打劫一次） */
    public double lastPirateKm = -1e9;
    /** 最近一次遭雷击时的已航行公里数（与上一次劈船拉开距离） */
    public double lastLightningKm = -1e9;
    /** 最近一次海上见闻彩蛋（飞鱼/海豚…）时的已航行公里数（防彩蛋连发） */
    public double lastAmbientKm = -1e9;
    /** 最近一次捞到漂流瓶时的已航行公里数（600 公里内最多一次） */
    public double lastBottleKm = -1e9;
    /** 本次航行各事件的发生次数（限流上限见 seafare_event.max_per_voyage：海盗≤2、漂流瓶≤5、雷击≤1、彩蛋≤10） */
    public int pirateCount;
    public int bottleCount;
    public int lightningCount;
    public int ambientCount;
    /** 出发前已就恶劣海况提醒过（第二次按「出发」才真正启航） */
    public boolean weatherWarned = false;
    /** 当前航次"心情OS"（由 weatherMood 写入，供界面常驻显示） */
    public String mood = "";
    /** 当前航次"事件"短标签（如「🏴 海盗来袭！」/「🌤 航行中，海面暂无异常」），供界面常驻显示 */
    public String event = "";
    /** 最新一次事件的完整叙事（叙事行 + 奖励/损失行 + 内心OS），保留到下一次事件覆盖；为空表示本航次暂无事件 */
    public List<String> eventDetail = new ArrayList<>();
    /** 海盗逼近，等玩家在「迎战 / 甩开 / 花钱消灾」之间做选择 */
    public boolean pendCombat = false;
}