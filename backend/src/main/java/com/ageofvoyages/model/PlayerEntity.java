package com.ageofvoyages.model;

import java.util.HashMap;
import java.util.Map;

/** 玩家持久化实体（对应 MySQL 的 players 表，MyBatis 使用）。
 *  字段保持 public 供 GameService 直接读写；MyBatis 结果映射通过下方 setter 注入。 */
public class PlayerEntity {

    public String clientId;
    public String name = "无名船长";
    public int gold = 1000;
    public String port = "london";
    public int cargoCap = 20;
    public Map<String, Integer> cargo = new HashMap<>();
    public boolean traveling = false;
    public String travelingTo = null;
    public long arriveAt = 0;

    public PlayerEntity() {
    }

    // MyBatis 结果映射需要 setter（列名下划线转驼峰：client_id→clientId 等）
    public void setClientId(String clientId) { this.clientId = clientId; }
    public void setName(String name) { this.name = name; }
    public void setGold(int gold) { this.gold = gold; }
    public void setPort(String port) { this.port = port; }
    public void setCargoCap(int cargoCap) { this.cargoCap = cargoCap; }
    public void setCargo(Map<String, Integer> cargo) { this.cargo = cargo; }
    public void setTraveling(boolean traveling) { this.traveling = traveling; }
    public void setTravelingTo(String travelingTo) { this.travelingTo = travelingTo; }
    public void setArriveAt(long arriveAt) { this.arriveAt = arriveAt; }
}
