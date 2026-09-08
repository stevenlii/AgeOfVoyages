package com.ageofvoyages.model;

/** 港口 / 途经地。携带真实经纬度，用于按地图实际位置计算航程（每击 10 公里）。 */
public record Port(String id, String name, String regionId, double lat, double lng) {
}