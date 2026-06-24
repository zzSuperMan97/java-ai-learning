package org.example.ailearning.utils;

import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.List;
import java.util.Map;

@Component
public class WordGenerator {

    public String generateReport(Map<String, Object> dataMap) throws Exception {
        // 1. 加载模板
        InputStream is = getClass().getClassLoader().getResourceAsStream("templates/report_template.docx");
        if (is == null) {
            throw new RuntimeException("找不到模板文件 report_template.docx");
        }

        XWPFDocument doc = new XWPFDocument(is);

        // 2. 核心逻辑：遍历所有段落，替换 {{key}}
        for (XWPFParagraph paragraph : doc.getParagraphs()) {
            replaceInParagraph(paragraph, dataMap);
        }

        // 如果有表格，也要遍历表格（如果你的模板里有表格的话）
        for (XWPFTable table : doc.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    for (XWPFParagraph p : cell.getParagraphs()) {
                        replaceInParagraph(p, dataMap);
                    }
                }
            }
        }

        // 3. 输出文件
        String fileName = "sales_report_" + System.currentTimeMillis() + ".docx";
        File dir = new File("outputs");
        if (!dir.exists()) dir.mkdirs();

        String filePath = "outputs/" + fileName;
        FileOutputStream fos = new FileOutputStream(filePath);
        doc.write(fos);

        fos.close();
        is.close();
        doc.close();

        return filePath;
    }

    /**
     * 辅助方法：替换段落中的文本
     * 注意：POI 中一个单词可能被拆分成多个 Run，这里做简化处理，假设 {{key}} 在一个 Run 内
     */
    private void replaceInParagraph(XWPFParagraph paragraph, Map<String, Object> dataMap) {
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs == null) return;

        for (XWPFRun run : runs) {
            String text = run.getText(0);
            if (text != null) {
                for (Map.Entry<String, Object> entry : dataMap.entrySet()) {
                    String key = "{{" + entry.getKey() + "}}";
                    if (text.contains(key)) {
                        text = text.replace(key, String.valueOf(entry.getValue()));
                        run.setText(text, 0);
                    }
                }
            }
        }
    }
}