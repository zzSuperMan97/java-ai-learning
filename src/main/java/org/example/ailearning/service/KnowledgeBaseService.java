package org.example.ailearning.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

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
     * 查出所有文档，在 Java 里算相似度，返回最相关的 topK 条
     */
    public List<String> searchBySimilarity(List<Double> queryVector, int topK) {
        // 查出所有文档
        List<Map<String, Object>> docs = jdbcTemplate.queryForList(
                "SELECT content, embedding FROM knowledge_base"
        );

        return docs.stream()
                .map(doc -> {
                    String content = (String) doc.get("content");
                    Double[] vectorArray = (Double[]) doc.get("embedding");
                    List<Double> docVector = List.of(vectorArray);
                    double similarity = cosineSimilarity(queryVector, docVector);
                    return Map.entry(content, similarity);
                })
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(topK)
                .map(Map.Entry::getKey)
                .toList();
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
