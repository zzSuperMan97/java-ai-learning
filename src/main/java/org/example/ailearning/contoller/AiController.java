package org.example.ailearning.contoller;

import jakarta.servlet.http.HttpServletResponse;
import org.example.ailearning.service.AiService;
import org.example.ailearning.service.KnowledgeBaseService;
import org.example.ailearning.utils.WordGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Controller
@RequestMapping("/ai")
public class AiController {

    @Autowired
    private AiService aiService;
    @Autowired
    private WordGenerator wordGenerator;
    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @GetMapping("/chat")
    @ResponseBody
    public String chat(@RequestParam String question) {
        // 调用 AI 服务
        String answer = aiService.chat(question);
        return "AI 回答: " + answer;
    }

    @GetMapping(value = "/chat-stream", produces = "text/event-stream;charset=UTF-8")
    @ResponseBody
    public SseEmitter chatStream(HttpServletResponse response, @RequestParam String question) {
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        // 设置超时10分钟
        SseEmitter sseEmitter = new SseEmitter(600000L);

        // 必须注册生命周期回调，防止连接泄漏、编码异常
        sseEmitter.onCompletion(() -> {
            System.out.println("SSE连接正常关闭");
        });
        sseEmitter.onError((ex) -> {
            System.err.println("SSE连接异常：" + ex.getMessage());
            sseEmitter.complete();
        });
        sseEmitter.onTimeout(() -> {
            System.out.println("SSE连接超时");
            sseEmitter.complete();
        });

        // 异步调用AI服务（不要在当前Tomcat同步线程阻塞）
        new Thread(() -> {
            try {
                aiService.streamChat(question, sseEmitter);
            } catch (Exception e) {
                sseEmitter.complete();
            }
        }).start();

        return sseEmitter;
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

    /**
     * 测试生成 Word 报告
     */
    @GetMapping("/test/generate-word")
    @ResponseBody
    public String testGenerateWord() throws Exception {
        // 模拟 AI 查出来的数据
        Map<String, Object> data = new HashMap<>();
        data.put("reporter", "张三");
        data.put("date", "2026-06-14");
        data.put("total_sales", "150");
        data.put("growth_rate", "12.5");
        data.put("top_product", "智能手机 X-Pro");
        data.put("ai_analysis", "本周销售额显著增长，主要得益于新品发布会的成功举办。建议下周加大线上推广力度。");
        data.put("next_plan", "1. 跟进大客户订单\n2. 优化库存管理");

        // 调用生成工具
        String filePath = wordGenerator.generateReport(data);

        return "✅ 报告已生成成功！\n文件路径: " + filePath;
    }

    @GetMapping("/embedding")
    @ResponseBody
    public String getEmbedding(@RequestParam String text) {
        List<Double> vector = aiService.getEmbedding(text);

        StringBuilder sb = new StringBuilder();
        sb.append("文本: ").append(text).append("\n\n");
        sb.append("向量维度: ").append(vector.size()).append("\n");
        sb.append("前10个数字: [");
        for (int i = 0; i < 10 && i < vector.size(); i++) {
            sb.append(String.format("%.6f", vector.get(i)));
            if (i < 9) sb.append(", ");
        }
        sb.append("] ...");

        return sb.toString();
    }

    @GetMapping("/similarity")
    @ResponseBody
    public String compareSimilarity(@RequestParam String text1, @RequestParam String text2) {
        List<Double> v1 = aiService.getEmbedding(text1);
        List<Double> v2 = aiService.getEmbedding(text2);
        double score = aiService.cosineSimilarity(v1, v2);

        return String.format("\"%s\" 和 \"%s\" 的相似度: %.4f", text1, text2, score);
    }

    /**
     * 添加文档到知识库
     */
    @PostMapping("/knowledge/add")
    @ResponseBody
    public String addKnowledge(@RequestParam String docId,
                               @RequestParam String content,
                               @RequestParam(required = false, defaultValue = "general") String category) {
        aiService.addToKnowledgeBase(docId, content, category);
        return "✅ 文档已存入知识库，当前共 " + knowledgeBaseService.count() + " 条";
    }

    /**
     * 数据库版 RAG 问答
     */
    @GetMapping("/rag-db")
    @ResponseBody
    public String ragFromDB(@RequestParam String question) {
        return aiService.ragChatFromDB(question);
    }

    @GetMapping(value = "/rag-stream", produces = "text/event-stream;charset=UTF-8")
    @ResponseBody
    public SseEmitter streamRagFromDB(HttpServletResponse response, @RequestParam String question) {
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        // 设置超时10分钟
        SseEmitter sseEmitter = new SseEmitter(600000L);

        // 必须注册生命周期回调，防止连接泄漏、编码异常
        sseEmitter.onCompletion(() -> {
            System.out.println("SSE连接正常关闭");
        });
        sseEmitter.onError((ex) -> {
            System.err.println("SSE连接异常：" + ex.getMessage());
            sseEmitter.complete();
        });
        sseEmitter.onTimeout(() -> {
            System.out.println("SSE连接超时");
            sseEmitter.complete();
        });

        // 异步调用AI服务（不要在当前Tomcat同步线程阻塞）
        new Thread(() -> {
            try {
                aiService.streamRagFromDB(question,sseEmitter);
            } catch (Exception e) {
                sseEmitter.complete();
            }
        }).start();

        return sseEmitter;
    }

}