package org.example.ailearning.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class AiService {
    // 从配置文件中读取 API Key
    @Value("${dashscope.api.key}")
    private String apiKey;

    // 内存中的"知识库"（实际项目中存在数据库里）
    private List<Map<String, Object>> knowledgeBase = new java.util.ArrayList<>();

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
     * 往知识库里添加文档
     */
    public void addDocument(String text) {
        List<Double> vector = getEmbedding(text);
        Map<String, Object> doc = new java.util.HashMap<>();
        doc.put("text", text);
        doc.put("vector", vector);
        knowledgeBase.add(doc);
        System.out.println("已添加文档: " + text.substring(0, Math.min(text.length(), 20)) + "...");
    }

    /**
     * 根据问题，从知识库中找出最相关的文档
     */
    public List<String> search(String question, int topK) {
        List<Double> questionVector = getEmbedding(question);

        // 计算每个文档和问题的相似度，排序
        List<Map.Entry<String, Double>> scores = new java.util.ArrayList<>();
        for (Map<String, Object> doc : knowledgeBase) {
            @SuppressWarnings("unchecked")
            List<Double> docVector = (List<Double>) doc.get("vector");
            double score = cosineSimilarity(questionVector, docVector);
            scores.add(Map.entry((String) doc.get("text"), score));
        }

        // 按相似度从高到低排序，取前 topK 个
        scores.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        List<String> results = new java.util.ArrayList<>();
        for (int i = 0; i < topK && i < scores.size(); i++) {
            results.add(scores.get(i).getKey());
        }
        return results;
    }

    /**
     * 完整的 RAG 问答：先检索，再生成
     */
    public String ragChat(String question) {
        // 1. 检索最相关的文档
        List<String> relevantDocs = search(question, 3);

        if (relevantDocs.isEmpty()) {
            return chat("请直接回答这个问题：" + question);
        }

        // 2. 拼接 Prompt
        StringBuilder prompt = new StringBuilder();
        prompt.append("请根据以下参考资料回答用户问题。如果参考资料不足以回答，就说根据已有资料无法确定。\n\n");
        prompt.append("参考资料：\n");
        for (int i = 0; i < relevantDocs.size(); i++) {
            prompt.append(i + 1).append(". ").append(relevantDocs.get(i)).append("\n");
        }
        prompt.append("\n用户问题：").append(question);

        // 3. 调用 AI 生成答案
        return chat(prompt.toString());
    }
}
