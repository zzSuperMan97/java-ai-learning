package org.example.ailearning.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Array;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class KnowledgeBaseService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 存入一条文档（原文 + 向量）
     */
    public void saveDocument(String docId, String content, String category, List<Double> embedding) {
        // 把 List<Double> 转成 PostgreSQL 数组格式
        Double[] vectorArray = embedding.toArray(new Double[0]);

        String sql = "INSERT INTO knowledge_base (doc_id, content, category, embedding, updated_at) " +
                "VALUES (?, ?, ?, ?, NOW()) " +
                "ON CONFLICT (doc_id) DO UPDATE SET content = ?, category = ?, embedding = ?, updated_at = NOW()";

        jdbcTemplate.update(sql, docId, content, category, vectorArray,
                content, category, vectorArray);

        System.out.println("✅ 文档已存入数据库: " + docId);
    }

    /**
     * 查出所有文档，在 Java 里算相似度，返回最相关的 topK 条（带阈值过滤）
     * @param queryVector 查询向量
     * @param topK 返回最多多少条
     * @param threshold 相似度阈值（0-1），低于此值的文档会被过滤掉
     */
    public List<String> searchBySimilarity(List<Double> queryVector, int topK, double threshold) {
// 把 List<Double> 转成 PostgreSQL 数组格式 "{0.1,0.2,0.3}"
        String arrayStr = queryVector.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",", "{", "}"));

        String sql = "SELECT content, cosine_similarity(embedding, ?::double precision[]) AS sim " +
                "FROM knowledge_base " +
                "WHERE cosine_similarity(embedding, ?::double precision[]) >= ? " +
                " AND array_length(embedding, 1) > 0 and array_length(embedding, 1) IS NOT NULL " +
                "ORDER BY sim DESC " +
                "LIMIT ?";

        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, arrayStr, arrayStr, threshold, topK);

        return results.stream()
                .map(row -> (String) row.get("content"))
                .toList();
    }

    /**
     * 兼容旧版本调用（默认阈值0.5）
     */
    public List<String> searchBySimilarity(List<Double> queryVector, int topK) {
        return searchBySimilarity(queryVector, topK, 0.5);
    }

    /**
     * 余弦相似度（和之前一样）
     */
    private double cosineSimilarity(List<Double> a, List<Double> b) {
        double dotProduct = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.size(); i++) {
            dotProduct += a.get(i) * b.get(i);
            normA += a.get(i) * a.get(i);
            normB += b.get(i) * b.get(i);
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 查看知识库有多少条文档
     */
    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM knowledge_base", Long.class);
        return count != null ? count : 0;
    }
}
