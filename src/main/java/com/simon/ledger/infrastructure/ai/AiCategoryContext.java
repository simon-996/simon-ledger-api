package com.simon.ledger.infrastructure.ai;

import java.util.List;
import java.util.LinkedHashSet;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;

/** The allowed labels are context for the AI, never a keyword classifier. */
public record AiCategoryContext(List<String> expenseCategories, List<String> incomeCategories) {
    private static final List<String> DEFAULT_EXPENSE = List.of("默认", "交通", "购物", "餐饮", "杂费", "娱乐", "居住");
    private static final List<String> DEFAULT_INCOME = List.of("默认", "工资", "兼职", "理财", "红包", "其他");

    public AiCategoryContext {
        expenseCategories = normalize(expenseCategories, DEFAULT_EXPENSE);
        incomeCategories = normalize(incomeCategories, DEFAULT_INCOME);
    }

    public static AiCategoryContext defaults() {
        return new AiCategoryContext(null, null);
    }

    public String retain(int type, String suggestion) {
        return suggestion != null && (type == 0 ? expenseCategories : incomeCategories).contains(suggestion)
                ? suggestion : null;
    }

    private static List<String> normalize(List<String> supplied, List<String> defaults) {
        if (supplied == null) return defaults;
        var normalized = new LinkedHashSet<String>();
        for (String label : supplied) {
            if (label == null) throw invalid();
            String value = label.strip();
            if (value.codePointCount(0, value.length()) > 64) throw invalid();
            if (!value.isEmpty()) normalized.add(value);
            if (normalized.size() > 128) throw invalid();
        }
        return List.copyOf(normalized);
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.BAD_REQUEST, "每类分类最多 128 个，每个分类最多 64 字且不能为 null");
    }
}
