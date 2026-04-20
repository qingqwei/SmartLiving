package com.hmdp.controller;

import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.AiChatResponse;
import com.hmdp.dto.Result;
import com.hmdp.service.IAiChatService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/ai")
public class AiController {
    private static final String SESSION_KEY_PREFIX = "ai:shop:session:";

    @Resource
    private IAiChatService aiChatService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @PostMapping("/shop/chat")
    public Result chat(@RequestBody AiChatRequest request) {
        AiChatResponse response = aiChatService.chat(request);
        return Result.ok(response);
    }

    @DeleteMapping("/shop/session/{sessionId}")
    public Result clearSession(@PathVariable("sessionId") String sessionId) {
        stringRedisTemplate.delete(SESSION_KEY_PREFIX + sessionId);
        return Result.ok();
    }
}
