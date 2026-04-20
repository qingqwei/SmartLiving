package com.hmdp.service.impl;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.AiChatMessage;
import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.AiChatResponse;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IAiChatService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.UserHolder;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class AiChatServiceImpl implements IAiChatService {
    private static final String SESSION_KEY_PREFIX = "ai:shop:session:";
    private static final String QA_CACHE_KEY_PREFIX = "ai:shop:qa:";
    private static final String DEFAULT_MODE = "fallback";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IShopService shopService;

    @Resource
    private IVoucherService voucherService;

    @Value("${ai.chat.api-key:}")
    private String apiKey;

    @Value("${ai.chat.base-url:}")
    private String baseUrl;

    @Value("${ai.chat.model:gpt-4o-mini}")
    private String model;

    @Value("${ai.chat.max-history:10}")
    private Integer maxHistory;

    @Value("${ai.chat.session-ttl-minutes:30}")
    private Long sessionTtlMinutes;

    @Value("${ai.chat.timeout-millis:20000}")
    private Integer timeoutMillis;

    @Value("${ai.chat.qa-cache-ttl-minutes:20}")
    private Long qaCacheTtlMinutes;

    @Override
    public AiChatResponse chat(AiChatRequest request) {
        validateRequest(request);
        String sessionId = resolveSessionId(request);
        List<AiChatMessage> history = loadHistory(sessionId);
        boolean cacheable = history.isEmpty();

        if (cacheable) {
            ChatAnswer cachedAnswer = loadCachedAnswer(request.getShopId(), request.getMessage());
            if (cachedAnswer != null) {
                saveHistory(sessionId, history, request.getMessage(), cachedAnswer.answer);
                return new AiChatResponse(sessionId, cachedAnswer.answer, cachedAnswer.toolUsed, "cache");
            }
        }

        ChatAnswer chatAnswer;
        try {
            if (isLlmConfigured()) {
                chatAnswer = askWithLlm(request.getShopId(), request.getMessage(), history);
            } else {
                chatAnswer = buildFallbackAnswer(request.getShopId(), request.getMessage());
            }
        } catch (Exception e) {
            log.warn("LLM chat failed, fallback to local response", e);
            chatAnswer = buildFallbackAnswer(request.getShopId(), request.getMessage());
        }

        saveHistory(sessionId, history, request.getMessage(), chatAnswer.answer);
        if (cacheable) {
            cacheAnswer(request.getShopId(), request.getMessage(), chatAnswer);
        }
        return new AiChatResponse(sessionId, chatAnswer.answer, chatAnswer.toolUsed, chatAnswer.mode);
    }

    private void validateRequest(AiChatRequest request) {
        if (request == null || request.getShopId() == null) {
            throw new IllegalArgumentException("shopId不能为空");
        }
        if (StrUtil.isBlank(request.getMessage())) {
            throw new IllegalArgumentException("message不能为空");
        }
    }

    private String resolveSessionId(AiChatRequest request) {
        if (StrUtil.isNotBlank(request.getSessionId())) {
            return request.getSessionId();
        }
        UserDTO user = UserHolder.getUser();
        if (user != null) {
            return "user:" + user.getId() + ":shop:" + request.getShopId();
        }
        return "guest:" + request.getShopId() + ":" + UUID.randomUUID().toString(true);
    }

    private boolean isLlmConfigured() {
        return StrUtil.isNotBlank(apiKey) && StrUtil.isNotBlank(baseUrl);
    }

    private List<AiChatMessage> loadHistory(String sessionId) {
        String sessionJson = stringRedisTemplate.opsForValue().get(SESSION_KEY_PREFIX + sessionId);
        if (StrUtil.isBlank(sessionJson)) {
            return new ArrayList<>();
        }
        JSONArray array = JSONUtil.parseArray(sessionJson);
        return JSONUtil.toList(array, AiChatMessage.class);
    }

    private void saveHistory(String sessionId, List<AiChatMessage> history, String userMessage, String answer) {
        List<AiChatMessage> updated = new ArrayList<>(history);
        updated.add(new AiChatMessage("user", userMessage));
        updated.add(new AiChatMessage("assistant", answer));
        while (updated.size() > maxHistory) {
            updated.remove(0);
        }
        stringRedisTemplate.opsForValue().set(
                SESSION_KEY_PREFIX + sessionId,
                JSONUtil.toJsonStr(updated),
                sessionTtlMinutes,
                TimeUnit.MINUTES
        );
    }

    private ChatAnswer askWithLlm(Long shopId, String userMessage, List<AiChatMessage> history) {
        List<Object> messages = new ArrayList<>();
        messages.add(message("system", buildSystemPrompt(shopId)));
        for (AiChatMessage historyMessage : history) {
            messages.add(message(historyMessage.getRole(), historyMessage.getContent()));
        }
        messages.add(message("user", userMessage));

        JSONObject firstResponse = doChatCompletion(messages, buildTools(), true);
        JSONObject firstMessage = firstResponse.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        JSONArray toolCalls = firstMessage.getJSONArray("tool_calls");

        if (toolCalls == null || toolCalls.isEmpty()) {
            String answer = firstMessage.getStr("content", "暂时无法生成回答，请稍后再试。");
            return new ChatAnswer(answer, Collections.emptyList(), "llm");
        }

        List<String> toolUsed = new ArrayList<>();
        messages.add(firstMessage);
        for (int i = 0; i < toolCalls.size(); i++) {
            JSONObject toolCall = toolCalls.getJSONObject(i);
            JSONObject function = toolCall.getJSONObject("function");
            String toolName = function.getStr("name");
            JSONObject arguments = JSONUtil.parseObj(function.getStr("arguments", "{}"));
            String toolResult = executeTool(toolName, arguments, shopId);
            toolUsed.add(toolName);

            JSONObject toolMessage = new JSONObject();
            toolMessage.set("role", "tool");
            toolMessage.set("tool_call_id", toolCall.getStr("id"));
            toolMessage.set("content", toolResult);
            messages.add(toolMessage);
        }

        JSONObject secondResponse = doChatCompletion(messages, null, false);
        JSONObject secondMessage = secondResponse.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        String finalAnswer = secondMessage.getStr("content", "暂时无法生成回答，请稍后再试。");
        return new ChatAnswer(finalAnswer, toolUsed, "llm");
    }

    private JSONObject doChatCompletion(List<Object> messages, List<Object> tools, boolean autoToolChoice) {
        JSONObject requestBody = new JSONObject();
        requestBody.set("model", model);
        requestBody.set("messages", messages);
        requestBody.set("temperature", 0.2);
        if (tools != null && !tools.isEmpty()) {
            requestBody.set("tools", tools);
            if (autoToolChoice) {
                requestBody.set("tool_choice", "auto");
            }
        }

        HttpResponse response = HttpRequest.post(baseUrl)
                .header(Header.AUTHORIZATION, "Bearer " + apiKey)
                .header(Header.CONTENT_TYPE, ContentType.JSON.getValue())
                .body(requestBody.toString())
                .timeout(timeoutMillis)
                .execute();

        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            throw new IllegalStateException("LLM request failed: " + response.getStatus() + " " + response.body());
        }
        return JSONUtil.parseObj(response.body());
    }

    private List<Object> buildTools() {
        return Arrays.asList(
                functionTool("getShopDetail", "查询店铺基础信息，如名称、地址、营业时间、评分、人均价格。"),
                functionTool("getShopVouchers", "查询店铺优惠券和秒杀券信息。")
        );
    }

    private JSONObject functionTool(String name, String description) {
        JSONObject shopIdField = new JSONObject();
        shopIdField.set("type", "integer");
        shopIdField.set("description", "店铺ID");

        JSONObject properties = new JSONObject();
        properties.set("shopId", shopIdField);

        JSONObject parameters = new JSONObject();
        parameters.set("type", "object");
        parameters.set("properties", properties);
        parameters.set("required", Collections.singletonList("shopId"));

        JSONObject function = new JSONObject();
        function.set("name", name);
        function.set("description", description);
        function.set("parameters", parameters);

        JSONObject tool = new JSONObject();
        tool.set("type", "function");
        tool.set("function", function);
        return tool;
    }

    private JSONObject message(String role, String content) {
        JSONObject message = new JSONObject();
        message.set("role", role);
        message.set("content", content);
        return message;
    }

    private String executeTool(String toolName, JSONObject arguments, Long defaultShopId) {
        Long shopId = defaultShopId;
        if ("getShopDetail".equals(toolName)) {
            return JSONUtil.toJsonStr(loadShopDetail(shopId));
        }
        if ("getShopVouchers".equals(toolName)) {
            return JSONUtil.toJsonStr(loadShopVouchers(shopId));
        }
        return "{\"error\":\"unsupported tool\"}";
    }

    private ChatAnswer buildFallbackAnswer(Long shopId, String userMessage) {
        List<String> tools = new ArrayList<>();
        boolean needShopDetail = containsAny(userMessage, "营业", "几点", "地址", "在哪", "评分", "人均", "店名", "介绍");
        boolean needVoucher = containsAny(userMessage, "券", "优惠", "秒杀", "便宜", "价格", "折扣");
        if (!needShopDetail && !needVoucher) {
            needShopDetail = true;
            needVoucher = true;
        }

        Map<String, Object> shopDetail = Collections.emptyMap();
        List<Map<String, Object>> vouchers = Collections.emptyList();
        if (needShopDetail) {
            shopDetail = loadShopDetail(shopId);
            tools.add("getShopDetail");
        }
        if (needVoucher) {
            vouchers = loadShopVouchers(shopId);
            tools.add("getShopVouchers");
        }
        return new ChatAnswer(composeFallbackAnswer(userMessage, shopDetail, vouchers), tools, DEFAULT_MODE);
    }

    private boolean containsAny(String message, String... keywords) {
        for (String keyword : keywords) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String composeFallbackAnswer(String message, Map<String, Object> shopDetail, List<Map<String, Object>> vouchers) {
        StringBuilder answer = new StringBuilder();
        if (!shopDetail.isEmpty()) {
            answer.append("店铺名称：").append(shopDetail.get("name")).append("。");
            if (message.contains("营业") || message.contains("几点")) {
                answer.append("营业时间：").append(shopDetail.get("openHours")).append("。");
                Object isOpen = shopDetail.get("isOpenNow");
                if (Boolean.TRUE.equals(isOpen)) {
                    answer.append("当前仍在营业。");
                } else if (Boolean.FALSE.equals(isOpen)) {
                    answer.append("当前暂未营业。");
                }
            }
            if (message.contains("地址") || message.contains("在哪")) {
                answer.append("地址：").append(shopDetail.get("address")).append("。");
            }
            if (message.contains("评分")) {
                answer.append("评分：").append(shopDetail.get("score")).append("。");
            }
            if (message.contains("人均")) {
                answer.append("人均：").append(shopDetail.get("avgPrice")).append("元。");
            }
        }
        if (!vouchers.isEmpty()) {
            answer.append("当前可用优惠券有");
            int limit = Math.min(2, vouchers.size());
            for (int i = 0; i < limit; i++) {
                Map<String, Object> voucher = vouchers.get(i);
                if (i > 0) {
                    answer.append("；");
                }
                answer.append(voucher.get("title"))
                        .append("，售价")
                        .append(voucher.get("payValue"))
                        .append("元");
                if (voucher.get("actualValue") != null) {
                    answer.append("，可抵扣").append(voucher.get("actualValue")).append("元");
                }
            }
            answer.append("。");
        }
        if (answer.length() == 0) {
            return "暂时未查询到这家店的完整信息，请稍后再试。";
        }
        if (!message.contains("营业") && !message.contains("地址") && !message.contains("优惠")) {
            answer.append("如果你想了解营业时间、地址或优惠券，我也可以继续帮你查。");
        }
        return answer.toString();
    }

    private Map<String, Object> loadShopDetail(Long shopId) {
        Shop shop = shopService.getById(shopId);
        if (shop == null) {
            return Collections.singletonMap("error", "shop not found");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", shop.getId());
        result.put("name", shop.getName());
        result.put("address", shop.getAddress());
        result.put("openHours", shop.getOpenHours());
        result.put("avgPrice", fenToYuan(shop.getAvgPrice()));
        result.put("score", shop.getScore() == null ? null : shop.getScore() / 10.0);
        result.put("typeId", shop.getTypeId());
        result.put("isOpenNow", isOpenNow(shop.getOpenHours()));
        return result;
    }

    private List<Map<String, Object>> loadShopVouchers(Long shopId) {
        Result result = voucherService.queryVoucherOfShop(shopId);
        if (result == null || result.getData() == null) {
            return Collections.emptyList();
        }
        JSONArray array = JSONUtil.parseArray(JSONUtil.toJsonStr(result.getData()));
        List<Voucher> vouchers = JSONUtil.toList(array, Voucher.class);
        List<Map<String, Object>> response = new ArrayList<>();
        for (Voucher voucher : vouchers) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", voucher.getId());
            item.put("title", voucher.getTitle());
            item.put("subTitle", voucher.getSubTitle());
            item.put("payValue", fenToYuan(voucher.getPayValue()));
            item.put("actualValue", fenToYuan(voucher.getActualValue()));
            item.put("type", voucher.getType());
            item.put("stock", voucher.getStock());
            item.put("beginTime", voucher.getBeginTime() == null ? null : voucher.getBeginTime().toString());
            item.put("endTime", voucher.getEndTime() == null ? null : voucher.getEndTime().toString());
            response.add(item);
        }
        return response;
    }

    private boolean isOpenNow(String openHours) {
        if (StrUtil.isBlank(openHours) || !openHours.contains("-")) {
            return false;
        }
        String[] times = openHours.split("-");
        try {
            LocalTime start = LocalTime.parse(times[0]);
            LocalTime end = LocalTime.parse(times[1]);
            LocalTime now = LocalTime.now();
            return !now.isBefore(start) && !now.isAfter(end);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private Double fenToYuan(Long fen) {
        if (fen == null) {
            return null;
        }
        return fen / 100.0;
    }

    private ChatAnswer loadCachedAnswer(Long shopId, String userMessage) {
        String cacheJson = stringRedisTemplate.opsForValue().get(buildQaCacheKey(shopId, userMessage));
        if (StrUtil.isBlank(cacheJson)) {
            return null;
        }
        JSONObject jsonObject = JSONUtil.parseObj(cacheJson);
        JSONArray tools = jsonObject.getJSONArray("toolUsed");
        List<String> toolUsed = tools == null ? Collections.emptyList() : JSONUtil.toList(tools, String.class);
        return new ChatAnswer(jsonObject.getStr("answer"), toolUsed, jsonObject.getStr("mode", "cache"));
    }

    private void cacheAnswer(Long shopId, String userMessage, ChatAnswer chatAnswer) {
        JSONObject cacheObject = new JSONObject();
        cacheObject.set("answer", chatAnswer.answer);
        cacheObject.set("toolUsed", chatAnswer.toolUsed);
        cacheObject.set("mode", chatAnswer.mode);
        stringRedisTemplate.opsForValue().set(
                buildQaCacheKey(shopId, userMessage),
                cacheObject.toString(),
                qaCacheTtlMinutes,
                TimeUnit.MINUTES
        );
    }

    private String buildQaCacheKey(Long shopId, String userMessage) {
        return QA_CACHE_KEY_PREFIX + shopId + ":" + SecureUtil.md5(StrUtil.trim(userMessage));
    }

    private String buildSystemPrompt(Long shopId) {
        return "你是本地生活平台的店铺智能助手。"
                + "你只回答当前店铺相关的问题。"
                + "当前会话绑定的店铺ID固定为" + shopId + "，禁止擅自改用其他店铺ID。"
                + "当问题涉及地址、营业时间、评分、人均价格、优惠券、秒杀券等信息时，优先调用工具获取真实数据。"
                + "不要编造营业时间、地址、优惠券库存等信息。"
                + "如果工具返回信息不足，请明确说明暂未查询到。"
                + "回答尽量简洁、准确、适合普通用户直接阅读。";
    }

    @AllArgsConstructor
    private static class ChatAnswer {
        private final String answer;
        private final List<String> toolUsed;
        private final String mode;
    }
}
