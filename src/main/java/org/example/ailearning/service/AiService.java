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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class AiService {
    // 从配置文件中读取 API Key
    @Value("${dashscope.api.key}")
    private String apiKey;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

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

    public List<Double> getEmbedding(String text) {
        try {
            TextEmbeddingParam param = TextEmbeddingParam.builder()
                    .apiKey(apiKey)
                    .model("text-embedding-v1")
                    .texts(java.util.Arrays.asList(text))
                    .build();

            TextEmbedding embedding = new TextEmbedding();
            TextEmbeddingResult result = embedding.call(param);

            return result.getOutput().getEmbeddings().get(0).getEmbedding();
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
        // 1. 问题转向量
        List<Double> queryVector = getEmbedding(question);

        // 2. 从数据库搜索最相关的文档（阈值0.5，低于此值的不要）
        List<String> relevantDocs = knowledgeBaseService.searchBySimilarity(queryVector, 3, 0.5);

        // 3. 如果没有检索到相关文档，直接告知用户
        if (relevantDocs.isEmpty()) {
            return "知识库中没有找到与您问题相关的信息，请尝试换个问法或补充知识库内容。";
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
        return chat(prompt.toString());
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
