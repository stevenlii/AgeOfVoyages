package com.ageofvoyages.controller;

import com.ageofvoyages.dto.ChatRequest;
import com.ageofvoyages.dto.ForecastRequest;
import com.ageofvoyages.dto.LoginRequest;
import com.ageofvoyages.dto.SailDecisionRequest;
import com.ageofvoyages.dto.SailRequest;
import com.ageofvoyages.dto.TradeRequest;
import com.ageofvoyages.dto.TravelRequest;
import com.ageofvoyages.service.GameService;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

@Controller
public class GameController {

    private final GameService game;

    public GameController(GameService game) {
        this.game = game;
    }

    @MessageMapping("login")
    public void login(LoginRequest r) { game.login(r); }

    @MessageMapping("travel")
    public void travel(TravelRequest r) { game.travel(r); }

    @MessageMapping("sail")
    public void sail(SailRequest r) { game.sail(r); }

    @MessageMapping("forecast")
    public void forecast(ForecastRequest r) { game.forecast(r.clientId()); }

    @MessageMapping("sailDecision")
    public void sailDecision(SailDecisionRequest r) { game.sailDecision(r); }

    @MessageMapping("trade")
    public void trade(TradeRequest r) { game.trade(r); }

    @MessageMapping("chat")
    public void chat(ChatRequest r) { game.chat(r); }
}
