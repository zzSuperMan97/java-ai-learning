package org.example.ailearning.contoller;

import org.example.ailearning.service.AiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/ai")
public class AiController {

    @Autowired
    private AiService aiService;

    @GetMapping("/chat")
    public String chat(@RequestParam String question) {
        // 调用 AI 服务
        String answer = aiService.chat(question);
        return "AI 回答: " + answer;
    }

    @GetMapping("/chat/async")
    public CompletableFuture<String> asyncChat(@RequestParam String question) {
        return aiService.asyncChat(question).thenApply(answer -> "【异步】AI 回答: " + answer);
    }

}