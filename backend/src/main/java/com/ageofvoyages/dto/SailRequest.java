package com.ageofvoyages.dto;

/** 手动航行：每点击一次前进/后退 10 公里 */
public record SailRequest(String clientId, String dir) {
}