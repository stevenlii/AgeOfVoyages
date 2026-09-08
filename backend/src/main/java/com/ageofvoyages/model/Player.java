package com.ageofvoyages.model;

import java.util.HashMap;
import java.util.Map;

/** 玩家状态（内存存储，实时状态；落库字段见 PlayerEntity） */
public class Player {
    public String name = "无名船长";
    public int gold = 1000;
    public String port = "london";
    public int cargoCap = 20;
    public Map<String, Integer> cargo = new HashMap<>();

    /** 手动航行状态：非 null 表示正在航行（traveling 的语义来源） */
    public Voyage voyage = null;

    /** 当前海上的天气（延续多击，偶尔渐变一次，不会每击都变天）；记忆态 */
    public String weather = null;

    // 下面字段仅为兼容 players 表映射保留，航行逻辑以 voyage 为准。
    public boolean traveling = false;
    public String travelingTo = null;
    public long arriveAt = 0;
}