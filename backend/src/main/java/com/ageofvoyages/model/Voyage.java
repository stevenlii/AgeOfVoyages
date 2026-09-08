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
    /** 当前「前方可进港」的途经地 id，null 表示无（需要玩家选择进港 / 继续） */
    public String offeredId;
    /** 航程逐步记录（每次点击的海况报告等），最多保留近 30 条 */
    public List<String> seaLog = new ArrayList<>();
    /** 已经「继续航行」略过的途经地 */
    public Set<String> passed = new HashSet<>();
    /** 最近一次遭遇海盗时的已航行公里数（保证每 300 公里内最多打劫一次） */
    public double lastPirateKm = -1e9;
    /** 最近一次遭雷击时的已航行公里数（与上一次劈船拉开距离） */
    public double lastLightningKm = -1e9;
}