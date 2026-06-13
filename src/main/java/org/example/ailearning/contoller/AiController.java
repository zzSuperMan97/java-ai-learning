package org.example.ailearning.contoller;

import org.example.ailearning.service.AiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

@Controller
@RequestMapping("/ai")
public class AiController {

    @Autowired
    private AiService aiService;

    @GetMapping("/chat")
    @ResponseBody
    public String chat(@RequestParam String question) {
        // 调用 AI 服务
        String answer = aiService.chat(question);
        return "AI 回答: " + answer;
    }

    @GetMapping("/chat/async")
    @ResponseBody
    public CompletableFuture<String> asyncChat(@RequestParam String question) {
        return aiService.asyncChat(question).thenApply(answer -> "【异步】AI 回答: " + answer);
    }

    @GetMapping("/")
    public String index() {
        return "chat"; // 返回 chat.html
    }

}