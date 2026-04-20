package com.hmdp.service;

import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.AiChatResponse;

public interface IAiChatService {

    AiChatResponse chat(AiChatRequest request);
}
