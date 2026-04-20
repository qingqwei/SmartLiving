package com.hmdp.dto;

import lombok.Data;

@Data
public class AiChatRequest {
    private Long shopId;
    private String message;
    private String sessionId;
}
