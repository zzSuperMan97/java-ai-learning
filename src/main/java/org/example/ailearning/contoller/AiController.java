package org.example.ailearning.contoller;

import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.util.StringUtil;
import org.example.ailearning.common.ThreadPoolConfig;
import org.example.ailearning.service.AiService;
import org.example.ailearning.service.KnowledgeBaseService;
import org.example.ailearning.utils.WordGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collector;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/ai")
public class AiController {

    @Autowired
    private AiService aiService;
    @Autowired
    private WordGenerator wordGenerator;
    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ThreadPoolConfig threadPoolConfig;

    @Autowired
    private ThreadPoolExecutor threadPoolExecutor;


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

    /**
     * 性能对比测试：量化 Redis 缓存带来的性能提升
     */
    @GetMapping("/performance-test")
    @ResponseBody
    public String performanceTest() {
        String testText = "苹果";
        String testQuestion = "哪款手机最畅销";

        // 清除缓存，确保第一次调用是无缓存状态
        redisTemplate.delete("embedding:" + testText);
        redisTemplate.delete("embedding:" + Math.abs(testQuestion.hashCode()));

        // 第一次调用：无缓存
        long embeddingStartNoCache = System.currentTimeMillis();
        aiService.getEmbedding(testText);
        long embeddingEndNoCache = System.currentTimeMillis();

        long ragStartNoCache = System.currentTimeMillis();
        aiService.ragChatFromDB(testQuestion);
        long ragEndNoCache = System.currentTimeMillis();

        // 第二次调用：命中缓存
        long embeddingStartWithCache = System.currentTimeMillis();
        aiService.getEmbedding(testText);
        long embeddingEndWithCache = System.currentTimeMillis();

        long ragStartWithCache = System.currentTimeMillis();
        aiService.ragChatFromDB(testQuestion);
        long ragEndWithCache = System.currentTimeMillis();

        // 计算耗时
        long embeddingTimeNoCache = embeddingEndNoCache - embeddingStartNoCache;
        long embeddingTimeWithCache = embeddingEndWithCache - embeddingStartWithCache;
        long ragTimeNoCache = ragEndNoCache - ragStartNoCache;
        long ragTimeWithCache = ragEndWithCache - ragStartWithCache;

        // 计算提升倍数（无缓存耗时 / 有缓存耗时）
        double embeddingSpeedup = embeddingTimeWithCache == 0 ? 0 : (double) embeddingTimeNoCache / embeddingTimeWithCache;
        double ragSpeedup = ragTimeWithCache == 0 ? 0 : (double) ragTimeNoCache / ragTimeWithCache;

        return String.format("{\n" +
                "  \"embedding\": { \"无缓存\": \"%dms\", \"有缓存\": \"%dms\", \"提升\": \"%.1f倍\" },\n" +
                "  \"rag查询\": { \"无缓存\": \"%dms\", \"有缓存\": \"%dms\", \"提升\": \"%.1f倍\" }\n" +
                "}", embeddingTimeNoCache, embeddingTimeWithCache, embeddingSpeedup,
                ragTimeNoCache, ragTimeWithCache, ragSpeedup);
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

    @GetMapping("/test/async-demo")
    @ResponseBody
    public String asyncDemo(@RequestParam String question) throws ExecutionException, InterruptedException {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            String chat = aiService.chat("你好");
            return chat;
        }).thenApply(str -> {
            return "【异步】"+str;
        });
        String s = future.get();
        return s;
    }

    @GetMapping("/test/parallel-embeddin")
    @ResponseBody
    public String parallelEmbedding(@RequestParam String question) throws ExecutionException, InterruptedException {
        List<String> list = new ArrayList<>(6);
        list.add("华北区退货200件");
        list.add("华东区销售1560件");
        list.add("产品库存9850件");
        list.add("西南区域订单2360件");
        list.add("华南财务对账620笔");
        list.add("西北物流配送3680单");
        list.add(null);
        long start = System.currentTimeMillis();
        ThreadPoolExecutor threadPoolExecutor = threadPoolConfig.getThreadPoolExecutor();
//        List<CompletableFuture<List<Double>>> collect = list.stream().map(str -> CompletableFuture.supplyAsync(() -> aiService.getEmbedding(str), threadPoolExecutor).exceptionally(ex->{
//            System.out.println("异步报错");
//            return List.of();
//        })).collect(Collectors.toList());

        List<CompletableFuture<List<Double>>> collect = list.stream().map(str -> CompletableFuture.supplyAsync(() -> aiService.getEmbedding(str), threadPoolExecutor)
        .handle((result, ex) -> {
            if (ex != null) {
                System.out.println("出错了: " + ex.getMessage());
                 return List.<Double>of();
            }
            return result;
        })).collect(Collectors.toList());

        CompletableFuture.allOf(collect.toArray(new CompletableFuture[0])).get();
        long end = System.currentTimeMillis();
        System.out.println(collect);
        return "消耗时常"+(end - start);

    }

    @GetMapping("/test/rag-async")
    @ResponseBody
    public String ragAsync(@RequestParam String question) throws ExecutionException, InterruptedException {
        String key = "rag:" + Math.abs(question.hashCode());
        CompletableFuture<List<Double>> embeddingFuture = CompletableFuture.supplyAsync(() -> {
            return aiService.getEmbedding(question);
        }, threadPoolExecutor);

        CompletableFuture<String> cacheFuture = CompletableFuture.supplyAsync(() -> {
            return redisTemplate.opsForValue().get(key);
        }, threadPoolExecutor);
        CompletableFuture.allOf(embeddingFuture,cacheFuture).get();
        if (!StringUtil.isBlank(cacheFuture.get())){
            return cacheFuture.get();
        }
        List<Double> doubles = embeddingFuture.get();
        List<String> relevantDocs = knowledgeBaseService.searchBySimilarity(doubles, 3, 0.5);

        if (relevantDocs.isEmpty()) {
            String result = "知识库中没有找到与您问题相关的信息，请尝试换个问法或补充知识库内容。";
            redisTemplate.opsForValue().set(key, result, 5, TimeUnit.MINUTES);
            return result;
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个专业的销售数据分析师 请根据以下参考资料回答用户问题 并且按照json格式返回。如果资料不足以回答，就说'根据已有资料无法确定'。\n\n");
        prompt.append("参考资料：\n");
        for (int i = 0; i < relevantDocs.size(); i++) {
            prompt.append(i + 1).append(". ").append(relevantDocs.get(i)).append("\n");
        }
        prompt.append("\n用户问题：").append(question);

        String answer = aiService.chat(prompt.toString());
        redisTemplate.opsForValue().set(key, answer, 1, TimeUnit.DAYS);
        return answer;

    }

}