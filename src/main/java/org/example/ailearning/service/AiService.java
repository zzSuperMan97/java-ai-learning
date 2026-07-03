package org.example.ailearning.service;

import com.alibaba.dashscope.aigc.codegeneration.CodeGenerationResult;
import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.base.HalfDuplexServiceParam;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.ResultCallback;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import org.apache.poi.util.StringUtil;
import org.example.ailearning.utils.StrTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class AiService {
    // 从配置文件中读取 API Key
    @Value("${dashscope.api.key}")
    private String apiKey;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 发送消息给通义千问，并获取回复
     * @param prompt 用户的问题
     * @return AI 的回答
     */
    public String chat(String prompt) {
        try {
            // 1. 构建消息对象
            Message systemMessage = Message.builder()
                    .role(Role.USER.getValue())
                    .content("你是一个专业的销售数据分析师，只根据提供的资料回答问题，不要编造数据。 没有数据就不要瞎说")
                    .build();

            Message userMessage = Message.builder()
                    .role(Role.USER.getValue())
                    .content(prompt)
                    .build();

            // 2. 构建请求参数
            GenerationParam param = GenerationParam.builder()
                    .apiKey(apiKey)
                    .model("qwen-turbo") // 使用通义千问 Turbo 模型，速度快且便宜
//                    .model("qwen-max") // 使用通义千问 Turbo 模型，速度快且便宜
                    .messages(Arrays.asList(systemMessage,userMessage))
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
                Message systemMessage = Message.builder()
                        .role(Role.USER.getValue())
                        .content(prompt)
                        .build();
                Message userMessage = Message.builder()
                        .role(Role.USER.getValue())
                        .content("你是一个专业的销售数据分析师，只根据提供的资料回答问题，不要编造数据。")
                        .build();


                // 2. 构建请求参数
                GenerationParam param = GenerationParam.builder()
                        .apiKey(apiKey)
                        .model("qwen-turbo") // 使用通义千问 Turbo 模型，速度快且便宜
                        .messages(Arrays.asList(systemMessage,userMessage))
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

    public List<Double> getEmbedding(String text) {
        try {
            String key = "embedding:"+Math.abs(text.hashCode());
            String str = redisTemplate.opsForValue().get(key);
            if (!StringUtil.isBlank(str)){
                return StrTool.convertSquareBracketToDoubleList(str);
            }

            TextEmbeddingParam param = TextEmbeddingParam.builder()
                    .apiKey(apiKey)
                    .model("text-embedding-v1")
                    .texts(java.util.Arrays.asList(text))
                    .build();

            TextEmbedding embedding = new TextEmbedding();
            TextEmbeddingResult result = embedding.call(param);
            List<Double> embedding1 = result.getOutput().getEmbeddings().get(0).getEmbedding();

            redisTemplate.opsForValue().set(key,embedding1.toString(),7, TimeUnit.DAYS);
            return embedding1;
        } catch (Exception e) {
            throw new RuntimeException("Embedding 调用失败", e);
        }
    }

    /**
     * 添加文档到知识库（存入数据库）
     */
    public void addToKnowledgeBase(String docId, String content, String category) {
        List<Double> vector = getEmbedding(content);
        knowledgeBaseService.saveDocument(docId, content, category, vector);
    }


    /**
     * 计算两个向量的余弦相似度（0~1，越接近 1 表示越相似）
     */
    public double cosineSimilarity(List<Double> a, List<Double> b) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.size(); i++) {
            dotProduct += a.get(i) * b.get(i);
            normA += a.get(i) * a.get(i);
            normB += b.get(i) * b.get(i);
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 数据库版 RAG 问答
     */
    public String ragChatFromDB(String question) {
        String key = "rag:"+Math.abs(question.hashCode());
        String answer = redisTemplate.opsForValue().get(key);
        if (!StringUtil.isBlank(answer)){
             return answer;
        }
        // 1. 问题转向量
        List<Double> queryVector = getEmbedding(question);

        // 2. 从数据库搜索最相关的文档（阈值0.5，低于此值的不要）
        List<String> relevantDocs = knowledgeBaseService.searchBySimilarity(queryVector, 3, 0.5);

        // 3. 如果没有检索到相关文档，直接告知用户
        if (relevantDocs.isEmpty()) {
            String result = "知识库中没有找到与您问题相关的信息，请尝试换个问法或补充知识库内容。";
            redisTemplate.opsForValue().set(key,result,5,TimeUnit.MINUTES);
            return result;
        }

        // 4. 拼接 Prompt
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个专业的销售数据分析师 请根据以下参考资料回答用户问题 并且按照json格式返回。如果资料不足以回答，就说'根据已有资料无法确定'。\n\n");
        prompt.append("参考资料：\n");
        for (int i = 0; i < relevantDocs.size(); i++) {
            prompt.append(i + 1).append(". ").append(relevantDocs.get(i)).append("\n");
        }
        prompt.append("\n用户问题：").append(question);
        String chat = chat(prompt.toString());
        redisTemplate.opsForValue().set(key,chat,1,TimeUnit.DAYS);
        // 5. 调用 AI 生成答案
        return chat;
    }

    /**
     * 数据库版 RAG 流式问答
     */
    public void streamRagFromDB(String question,SseEmitter sseEmitter) throws IOException {
        // 1. 问题转向量
        List<Double> queryVector = getEmbedding(question);

        // 2. 从数据库搜索最相关的文档（阈值0.5，低于此值的不要）
        List<String> relevantDocs = knowledgeBaseService.searchBySimilarity(queryVector, 3, 0.5);

        // 3. 如果没有检索到相关文档，直接告知用户
        if (relevantDocs.isEmpty()) {
            sseEmitter.send(SseEmitter.event()
                    .name("message")
                    .data("知识库中没有找到与您问题相关的信息，请尝试换个问法或补充知识库内容。"));
        }

        // 4. 拼接 Prompt
        StringBuilder prompt = new StringBuilder();
        prompt.append("请根据以下参考资料回答用户问题。如果资料不足以回答，就说'根据已有资料无法确定'。\n\n");
        prompt.append("参考资料：\n");
        for (int i = 0; i < relevantDocs.size(); i++) {
            prompt.append(i + 1).append(". ").append(relevantDocs.get(i)).append("\n");
        }
        prompt.append("\n用户问题：").append(question);

        // 5. 调用 AI 生成答案
        streamChat(prompt.toString(),sseEmitter);
    }




    /**
     * 发送消息给通义千问，并获取回复
     * @param prompt 用户的问题
     * @return AI 的回答
     */
    public void streamChat(String prompt, SseEmitter sseEmitter) {
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

            generation.streamCall(param, new ResultCallback<GenerationResult>() {
                @Override
                public void onEvent(GenerationResult result) {
                    String content = result.getOutput().getChoices().get(0).getMessage().getContent();
                    try {
                        String utf8Content = new String(content.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
                        utf8Content = utf8Content.replace("```json", "").replace("```", "").trim();
                        sseEmitter.send(SseEmitter.event()
                                .name("message")
                                .data(utf8Content));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public void onComplete() {
                    sseEmitter.complete();
                }

                @Override
                public void onError(Exception e) {
                    sseEmitter.completeWithError(e);
                }
            });
        } catch (ApiException | NoApiKeyException | InputRequiredException e) {
            e.printStackTrace();
        }
    }

}
