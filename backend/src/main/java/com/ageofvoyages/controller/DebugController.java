package com.ageofvoyages.controller;

import com.ageofvoyages.model.PlayerEntity;
import com.ageofvoyages.repository.PlayerMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 原型调试端点：直接看数据库里的玩家行，用来验证持久化是否生效。
 * ⚠️ 上线前请删除或用 @Profile("dev") 限制，避免暴露玩家数据。
 */
@RestController
@RequestMapping("/debug")
public class DebugController {

    private final PlayerMapper repo;

    public DebugController(PlayerMapper repo) {
        this.repo = repo;
    }

    @GetMapping("/player/{clientId}")
    public PlayerEntity player(@PathVariable String clientId) {
        return repo.findById(clientId);
    }
}
