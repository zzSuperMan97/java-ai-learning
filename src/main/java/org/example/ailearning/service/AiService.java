package org.example.ailearning.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

@Service
public class AiService {
    // 从配置文件中读取 API Key
    @Value("${dashscope.api.key}")
    private String apiKey;

    /**
     * 发送消息给通义千问，并获取回复
     * @param prompt 用户的问题
     * @return AI 的回答
     */
    public String chat(String prompt) {
        try {
            // 1. 构建消息对象
            Message userMessage = Message.builder()
                    .role(Role.USER.getValue())
                    .content(prompt)
                    .build();

            // 2. 构建请求参数
            GenerationParam param = GenerationParam.builder()
                    .apiKey(apiKey)
                    .model("qwen-turbo") // 使用通义千问 Turbo 模型，速度快且便宜
//                    .model("qwen-max") // 使用通义千问 Turbo 模型，速度快且便宜
                    .messages(Arrays.asList(userMessage))
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .build();

            // 3. 调用 API
            Generation generation = new Generation();
            GenerationResult result = generation.call(param);

            // 4. 提取并返回回答
            return result.getOutput().getChoices().get(0).getMessage().getContent();

        } catch (ApiException | NoApiKeyException | InputRequiredException e) {
            e.printStackTrace();
            return "AI 调用失败: " + e.getMessage();
        }
    }

    public CompletableFuture<String> asyncChat (String prompt) {
        return CompletableFuture.supplyAsync(()->{
            try {
                // 1. 构建消息对象
                Message userMessage = Message.builder()
                        .role(Role.USER.getValue())
                        .content(prompt)
                        .build();

                // 2. 构建请求参数
                GenerationParam param = GenerationParam.builder()
                        .apiKey(apiKey)
                        .model("qwen-turbo") // 使用通义千问 Turbo 模型，速度快且便宜
                        .messages(Arrays.asList(userMessage))
                        .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                        .build();

                // 3. 调用 API
                Generation generation = new Generation();
                GenerationResult result = generation.call(param);

                // 4. 提取并返回回答
                return result.getOutput().getChoices().get(0).getMessage().getContent();

            } catch (Exception e) {
                return "AI 调用失败: " + e.getMessage();
            }

        });
    }
}
