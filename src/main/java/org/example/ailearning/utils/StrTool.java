package org.example.ailearning.utils;

import java.util.ArrayList;
import java.util.List;

public class StrTool {

    /**
     * 解析 [数字,数字,...] 格式字符串为 List<Double>
     * @param str 源字符串，示例：[1212,12]、[1.5, -3.2, 8]
     * @return 转换后的浮点集合
     */
    public static List<Double> convertSquareBracketToDoubleList(String str) {
        List<Double> result = new ArrayList<>();

        // 1. 判空
        if (str == null || str.isBlank()) {
            return result;
        }

        String trimStr = str.trim();

        // 2. 校验必须以 [ 开头、] 结尾
        if (!trimStr.startsWith("[") || !trimStr.endsWith("]")) {
            return result;
        }

        // 3. 截取去掉首尾方括号
        String inner = trimStr.substring(1, trimStr.length() - 1).trim();
        if (inner.isBlank()) {
            return result;
        }

        // 4. 按逗号分割
        String[] numParts = inner.split(",");
        for (String part : numParts) {
            String numText = part.trim();
            if (numText.isBlank()) {
                continue;
            }
            try {
                // 转Double自动装箱存入集合
                Double num = Double.parseDouble(numText);
                result.add(num);
            } catch (NumberFormatException e) {
                // 非数字直接跳过，不中断程序
                System.out.println("无效数字：" + numText);
            }
        }
        return result;
    }
}
