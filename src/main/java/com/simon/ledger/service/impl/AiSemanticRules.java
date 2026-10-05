package com.simon.ledger.service.impl;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AiSemanticRules {
    private static final Pattern ALLOCATION_AMOUNT = Pattern.compile(
            "(?:承担|分摊|付|支付)了?\\s*(?:\\d+(?:\\.\\d{1,2})?|[零〇一二两三四五六七八九十百千万]+)(?!\\d)");
    private static final Pattern NEGATED_ALL = Pattern.compile(
            "(?:不是|并非|没有|未)\\s*(?:所有人|全体|全部人|大家|所有成员)"
                    + "|(?:所有人|全体|全部人|大家|所有成员)(?:都|全)?(?:没|未|不)");
    private static final Map<String, List<String>> CATEGORY_ALIASES = Map.ofEntries(
            Map.entry("住宿", List.of("住宿", "居住")),
            Map.entry("酒店", List.of("住宿", "居住")),
            Map.entry("宾馆", List.of("住宿", "居住")),
            Map.entry("住店", List.of("住宿", "居住")),
            Map.entry("房费", List.of("住宿", "居住")),
            Map.entry("早餐", List.of("餐饮")),
            Map.entry("午餐", List.of("餐饮")),
            Map.entry("晚餐", List.of("餐饮")),
            Map.entry("吃饭", List.of("餐饮")),
            Map.entry("聚餐", List.of("餐饮")),
            Map.entry("打车", List.of("交通")),
            Map.entry("出租车", List.of("交通")),
            Map.entry("公交", List.of("交通")),
            Map.entry("地铁", List.of("交通")),
            Map.entry("薪资", List.of("工资")));

    public List<AiParsingContext.PersonCandidate> match(String name, AiParsingContext context) {
        if (name == null || name.isBlank()) return List.of();
        return context.people().stream()
                .filter(person -> "我".equals(name) ? person.self() : name.equals(person.name()))
                .toList();
    }

    public LocalDate resolveDay(String expression, LocalDate reference) {
        if (expression == null || expression.isBlank()) return reference;
        String value = expression.trim();
        return switch (value) {
            case "今天" -> reference;
            case "昨天", "昨晚" -> reference.minusDays(1);
            case "前天" -> reference.minusDays(2);
            case "明天" -> reference.plusDays(1);
            case "上周一", "上周二", "上周三", "上周四", "上周五", "上周六", "上周日", "上周天" -> {
                String weekdays = "一二三四五六日";
                char day = value.charAt(2) == '天' ? '日' : value.charAt(2);
                yield reference.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .minusWeeks(1).plusDays(weekdays.indexOf(day));
            }
            default -> LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        };
    }

    public String category(String suggestion, int type, AiParsingContext context) {
        var candidates = type == 1 ? context.incomeCategories() : context.expenseCategories();
        if (suggestion == null || suggestion.isBlank()) return null;
        String value = suggestion.trim();
        if (candidates.contains(value)) return value;
        return CATEGORY_ALIASES.getOrDefault(value, List.of()).stream()
                .filter(candidates::contains).findFirst().orElse(null);
    }

    public boolean hasAllParticipantsEvidence(String source) {
        return containsAny(source, "所有人", "全体", "全部人", "大家", "所有成员")
                && !NEGATED_ALL.matcher(source).find();
    }

    public boolean hasDateEvidence(String source) {
        return containsAny(source, "今天", "昨天", "昨晚", "前天", "明天", "后天", "上周", "周末")
                || source.matches("(?s).*\\d{4}-\\d{1,2}-\\d{1,2}.*");
    }

    public boolean hasTimeEvidence(String source) {
        return source.matches("(?s).*\\d{1,2}[:：点时]\\d{1,2}.*")
                || containsAny(source, "凌晨", "早上", "上午", "中午", "下午", "晚上");
    }

    public boolean hasPaymentEvidence(String source) {
        return containsAny(source, "垫付", "代付", "先付", "先出", "付了", "支付", "付款", "付", "出了");
    }

    public boolean hasSharedPoolEvidence(String source) {
        return containsAny(source, "共同钱包", "公共钱包", "公款", "公共资金", "共同账户", "公共账户");
    }

    public boolean hasUnsupportedSplitEvidence(String source) {
        if (containsAny(source, "分别承担", "各自承担", "各付", "分别付", "分别垫付",
                "各自垫付", "分别代付", "各自代付", "分别付款", "各自付款", "不等额", "按比例", "分成")) {
            return true;
        }
        if (source.matches("(?s).*\\d+(?:\\.\\d+)?\\s*(?:元|块|块钱).+\\d+(?:\\.\\d+)?\\s*(?:元|块|块钱).*")) {
            return true;
        }
        Matcher allocationAmounts = ALLOCATION_AMOUNT.matcher(source);
        int allocationCount = 0;
        while (allocationAmounts.find()) {
            if (++allocationCount >= 2) return true;
        }
        return source.matches("(?s).*(?:[零〇一二两三四五六七八九十百千\\d]+)比(?:[零〇一二两三四五六七八九十百千\\d]+).*?");
    }

    private static boolean containsAny(String value, String... choices) {
        if (value == null) return false;
        for (String choice : choices) if (value.contains(choice)) return true;
        return false;
    }
}
