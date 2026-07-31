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
import com.alibaba.dashscope.tools.FunctionDefinition;
import com.alibaba.dashscope.tools.ToolBase;
import com.alibaba.dashscope.tools.ToolFunction;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import org.apache.poi.util.StringUtil;
import org.example.ailearning.utils.StrTool;
import org.example.ailearning.utils.WordGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
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

    @Autowired
    private WordGenerator wordGenerator;

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
                    .model("qwen-plus")
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

    /**
     * 带函数调用的聊天（Function Calling）
     */
    public String chatWithFunctions(String prompt, List<FunctionDefinition> functions) {
        try {
            // 1. 构建消息
            Message userMessage = Message.builder()
                    .role(Role.USER.getValue())
                    .content(prompt)
                    .build();

            // 2. 将 FunctionDefinition 包装成 ToolFunction
            List<ToolBase> tools = new ArrayList<>();
            for (FunctionDefinition func : functions) {
                tools.add(ToolFunction.builder().function(func).build());
            }

            // 3. 构建请求参数，使用 tools() 而不是 functions()
            GenerationParam param = GenerationParam.builder()
                    .apiKey(apiKey)
                    .model("qwen-plus")
                    .messages(Arrays.asList(userMessage))
                    .tools(tools)
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .build();

            // 4. 调用 API
            Generation generation = new Generation();
            GenerationResult result = generation.call(param);

            // 5. 检查是否需要调用函数
            Message responseMessage = result.getOutput().getChoices().get(0).getMessage();
            
            if (responseMessage.getToolCalls() != null && !responseMessage.getToolCalls().isEmpty()) {
                // LLM 决定调用函数
                Object toolCall = responseMessage.getToolCalls().get(0);
                // 使用反射获取 function 信息
                Method getFunctionMethod = toolCall.getClass().getMethod("getFunction");
                Object functionCall = getFunctionMethod.invoke(toolCall);
                
                Method getNameMethod = functionCall.getClass().getMethod("getName");
                Method getArgumentsMethod = functionCall.getClass().getMethod("getArguments");
                
                String functionName = (String) getNameMethod.invoke(functionCall);
                String arguments = (String) getArgumentsMethod.invoke(functionCall);
                
                System.out.println("LLM 决定调用函数: " + functionName);
                System.out.println("参数: " + arguments);
                
                // 6. 执行函数并获取结果
                String functionResult = executeFunction(functionName, arguments);
                if (functionResult.contains("系统查询失败")) return "系统查询失败";

                // 7. 将函数结果返回给 LLM 生成最终回答
                Message functionMessage = Message.builder()
                        .role("tool")
                        .content(functionResult)
                        .build();
                
                GenerationParam followUpParam = GenerationParam.builder()
                        .apiKey(apiKey)
                        .model("qwen-plus")
                        .messages(Arrays.asList(userMessage, responseMessage, functionMessage))
                        .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                        .build();
                
                GenerationResult followUpResult = generation.call(followUpParam);
                return followUpResult.getOutput().getChoices().get(0).getMessage().getContent();
                
            } else {
                // 不需要调用函数，直接返回回答
                return responseMessage.getContent();
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            return "AI 调用失败: " + e.getMessage();
        }
    }

    /**
     * 执行函数（根据函数名调用对应方法）
     */
    private String executeFunction(String functionName, String arguments) {
        try {
            if ("query_sales_data".equals(functionName)) {
                String region = extractJsonValue(arguments, "region");
                String month = extractJsonValue(arguments, "month");

                int i;
                for (i = 0; i < 3; i++) {
                    try {
                        Map<String, Object> result = querySalesData(region, month);
                        return new Gson().toJson(result);
                    }catch (Exception e){
                        e.getMessage();
                    }
                }
                return "系统查询失败";
            } else if ("query_inventory".equals(functionName)) {
                String region = extractJsonValue(arguments, "region");
                String product = extractJsonValue(arguments, "product");
                Map<String, Object> result = queryInventory(region, product);
                return new Gson().toJson(result);
            } else if ("query_customer".equals(functionName)) {
                String customerName = extractJsonValue(arguments, "customer_name");
                Map<String, Object> result = queryCustomer(customerName);
                return new Gson().toJson(result);
            } else if ("generate_report".equals(functionName)) {
                String content = extractJsonValue(arguments, "content");
                return generateReport(content);
            }
            return "{\"error\": \"未知函数\"}";
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 简单解析 JSON 中的值（实际项目建议用 Jackson/Gson）
     */
    private String extractJsonValue(String json, String key) {
        // 简单实现：{"region": "华东", "month": "2024-05"}
        JsonObject asJsonObject = JsonParser.parseString(json).getAsJsonObject();
        JsonElement jsonElement = asJsonObject.get(key);
        if (jsonElement == null) {
            return null;
        }
        return jsonElement.getAsString();
    }

    /**
     * 查询销售数据（模拟数据库查询）
     */
    public Map<String, Object> querySalesData(String region, String month) throws Exception {
        // 这里应该查数据库，先用模拟数据
        Map<String, Object> result = new HashMap<>();
        if (month == null || month.isEmpty()){
            result.put("error", "缺少month参数，请询问用户想查询的具体月份");
            return result;
        }
        if ("华东".equals(region) && "2024-05".equals(month)) {
            result.put("sales", 1500000);
            result.put("unit", "元");
            result.put("region", region);
            result.put("month", month);
        } else if ("华北".equals(region) && "2024-05".equals(month)) {
            result.put("sales", 800000);
            result.put("unit", "元");
            result.put("region", region);
            result.put("month", month);
        } else {
            result.put("error", "未找到相关数据");
        }
        
        return result;
    }

    /**
     * 查询库存数据（模拟数据库查询）
     */
    public Map<String, Object> queryInventory(String region, String product) {
        Map<String, Object> result = new HashMap<>();

        if ("华东".equals(region) && "手机".equals(product)) {
            result.put("inventory", 5200);
            result.put("unit", "台");
            result.put("region", region);
            result.put("product", product);
        } else if ("华北".equals(region) && "手机".equals(product)) {
            result.put("inventory", 3100);
            result.put("unit", "台");
            result.put("region", region);
            result.put("product", product);
        } else if ("华东".equals(region) && "笔记本".equals(product)) {
            result.put("inventory", 1800);
            result.put("unit", "台");
            result.put("region", region);
            result.put("product", product);
        } else {
            result.put("error", "未找到相关库存数据");
        }

        return result;
    }

    /**
     * 查询客户信息（模拟数据库查询）
     */
    public Map<String, Object> queryCustomer(String customerName) {
        Map<String, Object> result = new HashMap<>();

        if ("华为".equals(customerName)) {
            result.put("customer", "华为技术有限公司");
            result.put("level", "VIP");
            result.put("total_orders", 156);
            result.put("total_amount", 8500000);
            result.put("unit", "元");
            result.put("contact", "张经理");
        } else if ("小米".equals(customerName)) {
            result.put("customer", "小米科技有限公司");
            result.put("level", "金牌");
            result.put("total_orders", 89);
            result.put("total_amount", 3200000);
            result.put("unit", "元");
            result.put("contact", "李经理");
        } else {
            result.put("error", "未找到该客户信息");
        }

        return result;
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
                        .model("qwen3.7-plus") // 使用通义千问 Turbo 模型，速度快且便宜
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
                    .texts(Arrays.asList(text))
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
        prompt.append("你是一个专业的销售数据分析师，根据参考资料回答问题。\n\n");
        prompt.append("回答格式示例：\n");
        prompt.append("用户问题：华东区2024年5月销售额是多少？\n");
        prompt.append("回答：{\"region\":\"华东区\",\"period\":\"2024年5月\",\"metric\":\"销售额\",\"value\":\"150万元\"}\n\n");
        prompt.append("请先分析参考资料中有哪些相关数据，再给出最终回答。\n\n");
        prompt.append("禁止编造参考资料中没有的数据，如果某个字段在资料中找不到，该字段返回\"未知\"。\n");
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

    public void saveMessage(String sessionId, String role, String content) {
        String key = String.format("chat:history:%s",sessionId);
        redisTemplate.opsForHash().put(key,String.valueOf(System.currentTimeMillis()),String.format("{\"role\":\"%s\",\"content\":\"%s\"}",
                role, content));
        redisTemplate.expire(key, 30, TimeUnit.MINUTES);
    }

    public String getHistory(String sessionId, int lastN) {
        String key = String.format("chat:history:%s", sessionId);
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        if (entries.isEmpty()) {
            return "";
        }
        // 按时间戳排序，取最后 lastN 条
        return entries.entrySet().stream()
                .sorted((e1, e2) -> String.valueOf(e1.getKey()).compareTo(String.valueOf(e2.getKey())))
                .skip(Math.max(0, entries.size() - lastN))
                .map(entry -> {
                    String json = String.valueOf(entry.getValue());
                    Map<String, String> msg = new Gson().fromJson(json, new TypeToken<Map<String, String>>(){}.getType());
                    String role = msg.get("role");
                    String content = msg.get("content");
                    // 简单解析 JSON，提取 role 和 content
                    return role + "：" + content;
                })
                .collect(Collectors.joining("\n"));
    }


    private String handleChat(String history,String question,String sessionId) {
        // 组装 Prompt：历史对话 + 当前问题
        StringBuilder prompt = new StringBuilder();

        if (!history.isEmpty()) {
            prompt.append("以下是之前的对话记录：\n");
            prompt.append(history);
            prompt.append("\n\n");
        }

        prompt.append("用户当前问题：").append(question);

        return chatToken(prompt.toString(),sessionId);
    }


    private String handleQueryData(String question,String sessionId) {
        List<FunctionDefinition> functionDefinitions = creatQueryFunctions();
        return newChatWithFunctions(question,functionDefinitions,sessionId);
    }

    private String handleRag(String history,String question,String sessionId) {
        List<Double> queryVector = getEmbedding(question);
        List<String> relevantDocs = knowledgeBaseService.searchBySimilarity(queryVector, 3, 0.5);

        // 4. 组装 Prompt：系统角色 + 历史对话 + 参考资料 + 当前问题
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个专业的销售数据分析师，只根据提供的资料回答问题，不要编造数据。\n\n");

        if (!history.isEmpty()) {
            prompt.append("以下是之前的对话记录：\n");
            prompt.append(history);
            prompt.append("\n\n");
        }

        if (!relevantDocs.isEmpty()) {
            prompt.append("参考资料：\n");
            for (int i = 0; i < relevantDocs.size(); i++) {
                prompt.append(i + 1).append(". ").append(relevantDocs.get(i)).append("\n");
            }
            prompt.append("\n");
        } else {
            prompt.append("知识库中没有找到相关信息，如果用户问题无法回答，请告知。\n\n");
        }

        prompt.append("用户当前问题：").append(question);

        // 5. 调用 LLM
        return chatToken(prompt.toString(),sessionId);
    }

    /**
     * 带上下文的 RAG 多轮对话
     */
    public String chatWithHistory(String sessionId, String question) {
        // 2. 获取最近对话历史
        String history = getHistory(sessionId, 10);
        if (history.isEmpty()) {
            String welcome = "您好！我是销售数据助手，可以帮您：\n1. 查询销售数据\n2. 查询库存信息\n3. 查询客户信息\n4. 回答业务知识问题\n请问有什么可以帮您？";
            saveMessage(sessionId, "assistant", welcome);
//            return welcome;
        }

        // 1. 保存用户问题
        saveMessage(sessionId, "user", question);
        String key = String.format("chat:history:tokenCount:%s",sessionId);

        // 意图识别
        String intent = recognizeIntent(question);
        JsonObject asJsonObject = JsonParser.parseString(intent).getAsJsonObject();
        String intentStr = asJsonObject.get("intent").getAsString();


        String tokenCount = redisTemplate.opsForValue().get(key);
        if (tokenCount!=null && Integer.parseInt(tokenCount) >= 100) {
            // 压缩对话
            history = summarizeHistory(history,sessionId);
            redisTemplate.opsForValue().set(key,"0");
        }


        String answer = "";
        if ("chat".equals(intentStr)) {
            // 闲聊：不走 RAG，直接调 LLM
            answer = handleChat(history, question, sessionId);
        } else if ("query_data".equals(intentStr)) {
            // 查数据：走 Function Calling
            answer = handleQueryData(question,sessionId);
        } else if ("rag".equals(intentStr)) {
            // 3. RAG：检索知识库
            answer = handleRag(history, question, sessionId);
        } else if ("report".equals(intentStr)) {
            // 生成报告：暂时返回提示
            answer = handleQueryData(question,sessionId);
        }else if ("clear".equals(intentStr)) {
            // 清除历史对话
            answer = handleClearChat(sessionId);
            return answer;
        }else {
            // 闲聊：不走 RAG，直接调 LLM
            answer = handleChat(history, question, sessionId);
        }
        System.out.println(intentStr);
        saveMessage(sessionId, "assistant", answer);
        return answer;
    }

    private String handleClearChat(String sessionId) {
        String tokenCount = String.format("chat:history:tokenCount:%s",sessionId);
        String history = String.format("chat:history:%s",sessionId);
        redisTemplate.delete(tokenCount);
        redisTemplate.delete(history);
        return "清除成功";
    }

    /**
     * 发送消息给通义千问，并获取回复
     * @param prompt 用户的问题
     * @return AI 的回答
     */
    public String chatToken(String prompt,String sessionId) {
        String key = String.format("chat:history:tokenCount:%s",sessionId);
        String s = redisTemplate.opsForValue().get(key);
        try {
            // 1. 构建消息对象
            Message systemMessage = Message.builder()
                    .role(Role.SYSTEM.getValue())
                    .content("你是一个专业的销售数据分析师，只根据提供的资料回答问题，不要编造数据。 没有数据就不要瞎说")
                    .build();

            Message userMessage = Message.builder()
                    .role(Role.USER.getValue())
                    .content(prompt)
                    .build();

            // 2. 构建请求参数
            GenerationParam param = GenerationParam.builder()
                    .apiKey(apiKey)
                    .model("qwen-plus")
                    .messages(Arrays.asList(systemMessage,userMessage))
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .build();

            // 3. 调用 API
            Generation generation = new Generation();
            GenerationResult result = generation.call(param);
            Integer inputTokens = result.getUsage().getInputTokens();
            Integer outputTokens = result.getUsage().getOutputTokens();
            int oldCount = s != null ? Integer.parseInt(s) : 0;
            int newCount = oldCount + inputTokens + outputTokens;
            redisTemplate.opsForValue().set(key,String.valueOf(newCount));
            // 4. 提取并返回回答
            return result.getOutput().getChoices().get(0).getMessage().getContent();

        } catch (ApiException | NoApiKeyException | InputRequiredException e) {
            e.printStackTrace();
            return "AI 调用失败: " + e.getMessage();
        }
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
                    .model("qwen3.7-plus") // 使用通义千问 Turbo 模型，速度快且便宜
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

    private String summarizeHistory(String history,String sessionId) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是销售数据分析师。请将以下对话历史压缩为一段摘要，要求：\n" +
                "1. 保留用户提到的个人信息（姓名、部门等）\n" +
                "2. 保留所有查询过的数据结果（区域、月份、销售额等）\n" +
                "3. 保留用户的核心意图和未完成的请求\n" +
                "4. 不要编造任何数据，没有的信息不要写\n" +
                "5. 摘要控制在200字以内\n" +
                "6. 用简洁的陈述句，不要对话形式\n" +
                "\n" +
                "对话历史："+"\n");
        prompt.append(history);
        String chat = chat(prompt.toString());
        String key = String.format("chat:history:%s",sessionId);
        redisTemplate.delete(key);
        redisTemplate.opsForHash().put(key,String.valueOf(System.currentTimeMillis()),
                String.format("{\"role\":\"summary\",\"content\":\"%s\"}",chat));
        return chat;
    }

    private String recognizeIntent(String question) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是意图识别器。根据用户的问题，判断其意图类别。\n" +
                "\n" +
                "可选类别：\n" +
                "- chat：闲聊（你好、谢谢、再见等）\n" +
                "- query_data：查询业务数据（销售额、库存、客户信息等）\n" +
                "- rag：知识问答（公司介绍、产品说明、政策制度等）\n" +
                "- report：生成报告（月度报告、分析报告等）\n" +
                "- clear：清除对话\n" +
                "\n" +
                "要求：\n" +
                "1. 只返回 JSON，不要任何其他文字\n" +
                "2. 格式：{\"intent\": \"类别\"}\n" +
                "\n" +
                "用户问题："+"\n"+question);
        String chat = chat(prompt.toString());
        return chat;
    }

    public List<FunctionDefinition> creatQueryFunctions(){
        // 定义三个业务函数
        FunctionDefinition querySalesFunc = FunctionDefinition.builder()
                .name("query_sales_data")
                .description("查询指定区域和月份的销售额数据,month 参数必须是 YYYY-MM 格式，如 2024-05 需要用户提供月份")
                .parameters(createSalesQueryParameters())
                .build();

        FunctionDefinition queryInventoryFunc = FunctionDefinition.builder()
                .name("query_inventory")
                .description("查询指定区域和产品的库存数量")
                .parameters(createInventoryQueryParameters())
                .build();

        FunctionDefinition queryCustomerFunc = FunctionDefinition.builder()
                .name("query_customer")
                .description("查询指定客户的详细信息，包括等级、订单数、消费总额等")
                .parameters(createCustomerQueryParameters())
                .build();

        FunctionDefinition generateReportFunc = FunctionDefinition.builder()
                .name("generate_report")
                .description("根据分析内容生成Word格式的报告")
                .parameters(createReportFunction())
                .build();
        return Arrays.asList(querySalesFunc, queryInventoryFunc, queryCustomerFunc,generateReportFunc);
    }


    /**
     * 创建销售查询函数的参数定义
     */
    private JsonObject createSalesQueryParameters() {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        JsonObject regionProp = new JsonObject();
        regionProp.addProperty("type", "string");
        regionProp.addProperty("description", "区域名称，如华东、华北、华南等");
        properties.add("region", regionProp);

        JsonObject monthProp = new JsonObject();
        monthProp.addProperty("type", "string");
        monthProp.addProperty("description", "月份，格式为YYYY-MM，如2024-05");
        properties.add("month", monthProp);

        parameters.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("region");
        required.add("month");
        parameters.add("required", required);

        return parameters;
    }

    /**
     * 创建库存查询函数的参数定义
     */
    private JsonObject createInventoryQueryParameters() {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        JsonObject regionProp = new JsonObject();
        regionProp.addProperty("type", "string");
        regionProp.addProperty("description", "区域名称，如华东、华北、华南等");
        properties.add("region", regionProp);

        JsonObject productProp = new JsonObject();
        productProp.addProperty("type", "string");
        productProp.addProperty("description", "产品名称，如手机、笔记本等");
        properties.add("product", productProp);

        parameters.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("region");
        required.add("product");
        parameters.add("required", required);

        return parameters;
    }

    /**
     * 创建客户查询函数的参数定义
     */
    private JsonObject createCustomerQueryParameters() {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        JsonObject nameProp = new JsonObject();
        nameProp.addProperty("type", "string");
        nameProp.addProperty("description", "客户名称，如华为、小米等");
        properties.add("customer_name", nameProp);

        parameters.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("customer_name");
        parameters.add("required", required);

        return parameters;
    }

    /**
     * 创建报告生成函数的参数定义
     */
    private JsonObject createReportFunction() {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        JsonObject nameProp = new JsonObject();
        nameProp.addProperty("type", "string");
        nameProp.addProperty("description", "根据分析内容生成Word格式的报告，参数content为报告的分析内容");
        properties.add("content", nameProp);

        parameters.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("content");
        parameters.add("required", required);

        return parameters;
    }



    /**
     * 带函数调用的聊天（Function Calling）
     */
    public String newChatWithFunctions(String prompt, List<FunctionDefinition> functions, String sessionId) {
        try {
            List<Message> messages = new ArrayList<>(5);
            Message message1 = creatMessage("你是一个具备规划能力的智能助手。收到任务后，必须先输出【执行计划】和编号步骤列表，然后逐步执行。每完成一步，打印完成情况，继续下一步。", Role.SYSTEM.getValue());
            messages.add(message1);

            String key = String.format("agent:memory:%s",sessionId);
            String memory = redisTemplate.opsForValue().get(key);
            if (!StringUtil.isBlank(memory)){
                StringBuilder promptStr = new StringBuilder();
                promptStr.append("这是你之前的工作记录：\n");
                promptStr.append(memory);
                Message message = creatMessage(promptStr.toString(), Role.SYSTEM.getValue());
                messages.add(message);
            }

            // 1. 构建消息
            Message userMessage = creatMessage(prompt, Role.USER.getValue());
            messages.add(userMessage);
            // 2. 将 FunctionDefinition 包装成 ToolFunction
            List<ToolBase> tools = new ArrayList<>();
            for (FunctionDefinition func : functions) {
                tools.add(ToolFunction.builder().function(func).build());
            }
            int step = 0;
            int maxSteps = 5;
            JsonObject properties = new JsonObject();
            properties.addProperty("question",prompt);
            List<String> funcs = new ArrayList<>(4);
            while (step < maxSteps) {
                System.out.println("第" + (step + 1) + "轮, messages数量: " + messages.size());
                //  构建请求参数，使用 tools() 而不是 functions()
                GenerationParam param = GenerationParam.builder()
                        .apiKey(apiKey)
                        .model("qwen-plus")
                        .messages(messages)
                        .tools(tools)
                        .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                        .build();
                // 4. 调用 API
                Generation generation = new Generation();
                GenerationResult result = generation.call(param);

                // 5. 检查是否需要调用函数
                Message responseMessage = result.getOutput().getChoices().get(0).getMessage();
                System.out.println("Agent思考：" + responseMessage.getContent());
                if (responseMessage.getToolCalls() != null && !responseMessage.getToolCalls().isEmpty()) {                    // LLM 决定调用函数
                    Object toolCall = responseMessage.getToolCalls().get(0);
                    // 使用反射获取 function 信息
                    Method getFunctionMethod = toolCall.getClass().getMethod("getFunction");
                    Object functionCall = getFunctionMethod.invoke(toolCall);

                    Method getNameMethod = functionCall.getClass().getMethod("getName");
                    Method getArgumentsMethod = functionCall.getClass().getMethod("getArguments");

                    String functionName = (String) getNameMethod.invoke(functionCall);
                    String arguments = (String) getArgumentsMethod.invoke(functionCall);
                    funcs.add(functionName);
                    System.out.println("LLM 决定调用函数: " + functionName);
                    System.out.println("参数: " + arguments);

                    // 6. 执行函数并获取结果
                    String functionResult = executeFunction(functionName, arguments);

                    // 7. 将函数结果返回给 LLM 生成最终回答
                    Message functionMessage = Message.builder()
                            .role("tool")
                            .content(functionResult)
                            .build();
                    messages.add(responseMessage);
                    messages.add(functionMessage);
                } else {
                    // 不需要调用函数，直接返回回答
                    if (!StringUtil.isBlank(memory)) {
                        // 旧记忆 + 新记忆拼接
                        properties.addProperty("历史记录", memory);
                    }
                    properties.addProperty("本次问题", prompt);
                    properties.addProperty("回答结果", responseMessage.getContent());
                    properties.addProperty("访问函数", funcs.stream().collect(Collectors.joining("、")));
                    redisTemplate.opsForValue().set(key, properties.toString());
                    return responseMessage.getContent();
                }
                step ++;
            }
            return "未知数据不能判断";
        } catch (Exception e) {
            e.printStackTrace();
            return "AI 调用失败: " + e.getMessage();
        }
    }

    public Message creatMessage(String prompt,String role) {
        Message message = Message.builder()
                .role(role)
                .content(prompt)
                .build();
        return message;
    }

    public String generateReport(String content) throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("reporter", "张三");
        data.put("date", "2026-06-14");
        data.put("total_sales", "150");
        data.put("growth_rate", "12.5");
        data.put("top_product", "智能手机 X-Pro");
        data.put("ai_analysis", content);
        data.put("next_plan", "1. 跟进大客户订单\n2. 优化库存管理");
        return wordGenerator.generateReport(data);
    }
}
