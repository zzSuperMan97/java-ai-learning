package org.example.ailearning.contoller;

import org.example.ailearning.service.AiService;
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
    private WordGenerator wordGenerator; // 2. 注入工具类


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

    // 启动时加载一些示例知识（模拟从数据库加载）
    @GetMapping("/init-knowledge")
    @ResponseBody
    public String initKnowledge() {
        aiService.addDocument("2024年5月，华东区销售额达到150万元，环比增长12%。");
        aiService.addDocument("2024年5月，华北区销售额达到80万元，环比下降5%。");
        aiService.addDocument("智能手机X-Pro是公司最畅销的产品，占总销售额的40%。");
        aiService.addDocument("公司计划在6月推出一款新的平板电脑产品线。");
        aiService.addDocument("华南区客户满意度调查显示，售后服务评分最高，达到4.8分。");
        return "✅ 知识库已初始化，共加载 5 条文档";
    }

    // RAG 问答接口
    @GetMapping("/rag-chat")
    @ResponseBody
    public String ragChat(@RequestParam String question) {
        return aiService.ragChat(question);
    }
}