package com.ageofvoyages.model;

import java.util.HashMap;
import java.util.Map;

/** 玩家状态（内存存储，原型阶段不落库） */
public class Player {
    public String name = "无名船长";
    public int gold = 1000;
    public String port = "london";
    public int cargoCap = 20;
    public Map<String, Integer> cargo = new HashMap<>();
    public boolean traveling = false;
    public String travelingTo = null;
    public long arriveAt = 0;
}
