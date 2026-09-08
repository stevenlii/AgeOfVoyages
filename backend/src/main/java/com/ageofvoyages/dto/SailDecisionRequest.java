package com.ageofvoyages.dto;

/** 途经地决策：进港 或 继续航行 */
public record SailDecisionRequest(String clientId, String choice) {
}