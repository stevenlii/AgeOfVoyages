package com.ageofvoyages.model;

/** 海上遭遇配置（一行一个事件，对应 MySQL 的 seafare_event 表）。
 *  字段保持 public 供 GameService 直接读写；MyBatis 结果映射通过下方 setter 注入。 */
public class SeafareEventEntity {

    public int id;
    public String eventCode;
    public String eventName;
    public double perKm;
    public String seaReq;
    public double minStartKm;
    public double minSpacingKm;
    public int maxPerVoyage;
    public double minVoyageKm;
    public String remark;

    public SeafareEventEntity() {
    }

    // MyBatis 结果映射需要的 setter（列名下划线转驼峰）
    public void setId(int v) { this.id = v; }
    public void setEventCode(String v) { this.eventCode = v; }
    public void setEventName(String v) { this.eventName = v; }
    public void setPerKm(double v) { this.perKm = v; }
    public void setSeaReq(String v) { this.seaReq = v; }
    public void setMinStartKm(double v) { this.minStartKm = v; }
    public void setMinSpacingKm(double v) { this.minSpacingKm = v; }
    public void setMaxPerVoyage(int v) { this.maxPerVoyage = v; }
    public void setMinVoyageKm(double v) { this.minVoyageKm = v; }
    public void setRemark(String v) { this.remark = v; }
}