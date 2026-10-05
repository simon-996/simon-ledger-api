package com.simon.ledger.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.dto.resp.AiDraftResp;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class AiSemanticDraftValidator {
    private static final List<String> PAYMENT_VERBS =
            List.of("垫付", "代付", "先付", "先出", "付了", "支付", "付款", "付", "出了");
    private static final Pattern ISO_DATE_NUMBER = Pattern.compile("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}");
    private final ObjectMapper mapper;
    private final AiSemanticRules rules;
    private final AiAmountExpressionParser amounts = new AiAmountExpressionParser();

    public AiSemanticDraftValidator(ObjectMapper mapper, AiSemanticRules rules) {
        this.mapper = mapper;
        this.rules = rules;
    }

    public AiDraftResp validate(String providerJson, AiParsingContext context) {
        final JsonNode root;
        try {
            root = mapper.readTree(providerJson);
        } catch (Exception exception) {
            throw invalid();
        }
        JsonNode entries = root == null || !root.isObject() ? null : root.get("entries");
        if (entries == null || !entries.isArray() || entries.isEmpty() || entries.size() > 10) {
            throw invalid();
        }
        AiDraftResp result = new AiDraftResp();
        for (JsonNode raw : entries) result.getEntries().add(validateEntry(raw, context));
        return result;
    }

    private AiDraftResp.Entry validateEntry(JsonNode raw, AiParsingContext context) {
        if (!raw.isObject()) throw invalid();
        AiDraftResp.Entry entry = new AiDraftResp.Entry();
        entry.setSchemaVersion(2);
        String source = requiredText(raw, "sourceText", 1000);
        if (context.text() == null || !context.text().contains(source)) throw invalid();
        entry.setSourceText(source);

        JsonNode typeNode = raw.get("type");
        if (typeNode == null || !typeNode.isIntegralNumber() || (typeNode.intValue() != 0 && typeNode.intValue() != 1)) {
            throw invalid();
        }
        int type = typeNode.intValue();
        entry.setType(type);
        String amountValue = requiredText(raw, "amount", 32);
        if (!amountValue.matches("\\d+(?:\\.\\d{1,2})?")) throw invalid();
        final BigDecimal amount;
        try {
            amount = new BigDecimal(amountValue);
        } catch (NumberFormatException exception) {
            throw invalid();
        }
        if (amount.signum() <= 0 || amount.scale() > 2 || amount.precision() > 12) throw invalid();
        String amountExpression = requiredText(raw, "amountExpression", 64);
        if (!containsValidAmountEvidence(source, amountExpression)) throw amountMismatch();
        try {
            if (amounts.parse(amountExpression).compareTo(amount) != 0) throw amountMismatch();
        } catch (IllegalArgumentException exception) {
            throw amountMismatch();
        }
        entry.setAmount(amount.setScale(2));
        entry.getFieldSources().put("amount", "EXPLICIT");

        String currencyCode = requiredText(raw, "currencyCode", 3).toUpperCase(Locale.ROOT);
        try {
            Currency.getInstance(currencyCode);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
        entry.setCurrencyCode(currencyCode);
        if (!currencyCode.equals("CNY") && !currencyCode.equals(context.currencyCode())) {
            issue(entry, "currencyCode", "CURRENCY_UNSUPPORTED", currencyCode, List.of());
        }
        Set<String> mentionedCurrencies = currenciesMentioned(source);
        if (mentionedCurrencies.size() > 1 || (!mentionedCurrencies.isEmpty()
                && !mentionedCurrencies.contains(currencyCode))) {
            issue(entry, "currencyCode", "CONFLICTING_FIELDS", currencyCode, List.of());
        }
        entry.getFieldSources().put("currencyCode", mentionedCurrencies.isEmpty() ? "DEFAULT" : "EXPLICIT");

        String originalCategory = requiredNullableText(raw, "categorySuggestion", 64);
        entry.setCategoryOriginalSuggestion(originalCategory);
        String category = rules.category(originalCategory, type, context);
        if (category == null) {
            entry.setCategorySuggestion(originalCategory);
            issue(entry, "category", "CATEGORY_UNMATCHED", originalCategory, List.of());
        } else {
            entry.setCategorySuggestion(category);
            entry.getFieldSources().put("category",
                    source.contains(originalCategory) && originalCategory.equals(category) ? "EXPLICIT" : "SUGGESTED");
        }

        entry.setNote(requiredNullableText(raw, "note", 512));
        if (entry.getNote() != null) entry.getFieldSources().put("note", "SUGGESTED");
        resolveDate(entry, raw, source, context);
        resolvePayment(entry, raw, source, type, context);
        resolveParticipants(entry, raw, source, context);
        resolveSplit(entry, raw, source, context);
        return entry;
    }

    private void resolveDate(AiDraftResp.Entry entry, JsonNode raw, String source, AiParsingContext context) {
        entry.setReferenceDate(context.referenceDate().toString());
        entry.setReferenceZone(context.zone().getId());
        String expression = requiredNullableText(raw, "dateExpression", 64);
        String happenedAtValue = requiredNullableText(raw, "happenedAt", 40);
        LocalDate day;
        boolean explicit = expression != null;
        if (expression != null) {
            if (!source.contains(expression)) {
                issue(entry, "happenedAt", "DATE_AMBIGUOUS", expression, List.of());
                return;
            }
            try {
                day = rules.resolveDay(expression, context.referenceDate());
            } catch (DateTimeException exception) {
                issue(entry, "happenedAt", "DATE_AMBIGUOUS", expression, List.of());
                return;
            }
        } else if (rules.hasDateEvidence(source)) {
            issue(entry, "happenedAt", "DATE_AMBIGUOUS", null, List.of());
            return;
        } else {
            day = context.referenceDate();
        }

        if (day.isAfter(context.referenceDate())) {
            issue(entry, "happenedAt", "DATE_INVALID", expression, List.of());
        }
        if (happenedAtValue == null) {
            entry.setHappenedAt(day.atStartOfDay());
            entry.setDatePrecision("DAY");
            entry.getFieldSources().put("happenedAt", explicit ? "EXPLICIT" : "DEFAULT");
            return;
        }
        try {
            LocalDateTime dateTime = LocalDateTime.parse(happenedAtValue);
            if (!dateTime.toLocalDate().equals(day)) {
                issue(entry, "happenedAt", "DATE_AMBIGUOUS", happenedAtValue, List.of());
                entry.setHappenedAt(day.atStartOfDay());
                entry.setDatePrecision("DAY");
                return;
            }
            List<java.time.ZoneOffset> offsets = context.zone().getRules().getValidOffsets(dateTime);
            if (offsets.isEmpty() || offsets.size() > 1) {
                issue(entry, "happenedAt", "DATE_AMBIGUOUS", happenedAtValue, List.of());
                entry.setHappenedAt(day.atStartOfDay());
                entry.setDatePrecision("DAY");
                return;
            }
            LocalTime time = dateTime.toLocalTime();
            if (!time.equals(LocalTime.MIDNIGHT) && !hasMatchingClockTime(source, time)) {
                issue(entry, "happenedAt", "DATE_AMBIGUOUS", happenedAtValue, List.of());
                entry.setHappenedAt(day.atStartOfDay());
                entry.setDatePrecision("DAY");
                return;
            }
            entry.setHappenedAt(dateTime);
            entry.setDatePrecision(time.equals(LocalTime.MIDNIGHT) ? "DAY" : "TIME");
            entry.getFieldSources().put("happenedAt", explicit ? "EXPLICIT" : "DEFAULT");
        } catch (DateTimeException exception) {
            issue(entry, "happenedAt", "DATE_AMBIGUOUS", happenedAtValue, List.of());
            entry.setHappenedAt(day.atStartOfDay());
            entry.setDatePrecision("DAY");
        }
    }

    private void resolvePayment(AiDraftResp.Entry entry, JsonNode raw, String source, int type,
                                AiParsingContext context) {
        String requestedMode = requiredText(raw, "paymentMode", 32);
        if (!List.of("PERSON_PAID", "SHARED_POOL", "UNKNOWN").contains(requestedMode)) throw invalid();
        if (type == 1) {
            entry.setPaymentMode("UNKNOWN");
            entry.setPayerPersonUuid(null);
            return;
        }
        String payerName = requiredNullableText(raw, "payerName", 64);
        entry.setPaymentMode(requestedMode);
        if (requestedMode.equals("UNKNOWN")) {
            issue(entry, "paymentMode", "PAYMENT_UNSPECIFIED", null, List.of());
            return;
        }
        boolean sharedEvidence = rules.hasSharedPoolEvidence(source);
        boolean paymentEvidence = rules.hasPaymentEvidence(source);
        if (requestedMode.equals("SHARED_POOL")) {
            entry.setPayerPersonUuid(null);
            if (payerName != null || (paymentEvidence && hasDirectNamedPaymentEvidence(source, context))) {
                issue(entry, "paymentMode", "CONFLICTING_FIELDS", payerName, List.of());
            } else if (!sharedEvidence) {
                issue(entry, "paymentMode", "PAYMENT_UNSPECIFIED", null, List.of());
            } else {
                entry.getFieldSources().put("paymentMode", "EXPLICIT");
                entry.getFieldSources().put("payer", "EXPLICIT");
            }
            return;
        }
        if (sharedEvidence) {
            issue(entry, "paymentMode", "CONFLICTING_FIELDS", payerName, List.of());
        }
        if (hasMultipleNamedPayers(source, context)) {
            entry.setPayerPersonUuid(null);
            issue(entry, "payer", "PAYER_UNSPECIFIED", null, List.of());
            return;
        }
        if (payerName == null) {
            issue(entry, "payer", "PAYER_UNSPECIFIED", null, List.of());
            return;
        }
        var matches = rules.match(payerName, context);
        if (matches.size() != 1) {
            issue(entry, "payer", matches.size() > 1 ? "PERSON_AMBIGUOUS" : "PERSON_NOT_FOUND",
                    payerName, matches.stream().map(AiParsingContext.PersonCandidate::uuid).toList());
            return;
        }
        if (!paymentEvidence || !hasPayerEvidence(source, payerName, matches.getFirst(), context)) {
            issue(entry, "payer", "PAYER_UNSPECIFIED", payerName, matches.stream()
                    .map(AiParsingContext.PersonCandidate::uuid).toList());
            return;
        }
        entry.setPayerPersonUuid(matches.getFirst().uuid());
        entry.getFieldSources().put("paymentMode", "EXPLICIT");
        entry.getFieldSources().put("payer", "EXPLICIT");
    }

    private void resolveParticipants(AiDraftResp.Entry entry, JsonNode raw, String source,
                                    AiParsingContext context) {
        String scope = requiredText(raw, "participantScope", 32);
        if (!List.of("ALL", "SPECIFIED", "UNKNOWN").contains(scope)) throw invalid();
        List<String> names = requiredTextList(raw, "personNames", 30, 64);
        List<String> excludedNames = requiredTextList(raw, "excludedPersonNames", 30, 64);
        var selected = new LinkedHashSet<String>();
        boolean allEvidence = rules.hasAllParticipantsEvidence(source);
        if (scope.equals("ALL")) {
            if (!allEvidence) {
                issue(entry, "participants", "CONFLICTING_FIELDS", null, List.of());
            } else {
                context.people().forEach(person -> selected.add(person.uuid()));
                entry.getFieldSources().put("participants", "EXPLICIT");
            }
            if (!names.isEmpty()) issue(entry, "participants", "CONFLICTING_FIELDS", names.getFirst(), List.of());
        } else if (scope.equals("SPECIFIED")) {
            if (allEvidence) issue(entry, "participants", "CONFLICTING_FIELDS", null, List.of());
            if (names.isEmpty()) {
                issue(entry, "participants", "PARTICIPANTS_UNSPECIFIED", null, List.of());
            }
            for (String name : names) {
                var matches = rules.match(name, context);
                if (!source.contains(name)) {
                    issue(entry, "participants", "PERSON_NOT_FOUND", name, matches.stream()
                            .map(AiParsingContext.PersonCandidate::uuid).toList());
                } else if (matches.size() != 1) {
                    issue(entry, "participants", matches.size() > 1 ? "PERSON_AMBIGUOUS" : "PERSON_NOT_FOUND",
                            name, matches.stream().map(AiParsingContext.PersonCandidate::uuid).toList());
                } else {
                    selected.add(matches.getFirst().uuid());
                }
            }
            if (!names.isEmpty() && selected.size() == names.size()) {
                entry.getFieldSources().put("participants", "EXPLICIT");
            }
        } else {
            issue(entry, "participants", "PARTICIPANTS_UNSPECIFIED", null, List.of());
            if (!names.isEmpty() || !excludedNames.isEmpty() || allEvidence) {
                issue(entry, "participants", "CONFLICTING_FIELDS", null, List.of());
            }
        }

        if (!excludedNames.isEmpty() && !scope.equals("ALL")) {
            issue(entry, "excludedParticipants", "CONFLICTING_FIELDS", excludedNames.getFirst(), List.of());
        }
        for (String name : excludedNames) {
            var matches = rules.match(name, context);
            if (!source.contains(name) || !hasExclusionEvidence(source, name)) {
                issue(entry, "excludedParticipants", "CONFLICTING_FIELDS", name, matches.stream()
                        .map(AiParsingContext.PersonCandidate::uuid).toList());
            } else if (matches.size() != 1) {
                issue(entry, "excludedParticipants", matches.size() > 1 ? "PERSON_AMBIGUOUS" : "PERSON_NOT_FOUND",
                        name, matches.stream().map(AiParsingContext.PersonCandidate::uuid).toList());
            } else if (names.contains(name)) {
                issue(entry, "excludedParticipants", "CONFLICTING_FIELDS", name, matches.stream()
                        .map(AiParsingContext.PersonCandidate::uuid).toList());
            } else {
                selected.remove(matches.getFirst().uuid());
                entry.getFieldSources().put("excludedParticipants", "EXPLICIT");
            }
        }
        entry.setParticipantScope(scope);
        entry.setPersonUuids(List.copyOf(selected));
        if (selected.isEmpty()) issue(entry, "participants", "PARTICIPANTS_UNSPECIFIED", null, List.of());
    }

    private void resolveSplit(AiDraftResp.Entry entry, JsonNode raw, String source,
                              AiParsingContext context) {
        String split = requiredText(raw, "splitMode", 32);
        if (!List.of("EQUAL", "UNSUPPORTED", "UNKNOWN").contains(split)) throw invalid();
        if (rules.hasUnsupportedSplitEvidence(source) || hasMultipleNamedPayers(source, context)) {
            entry.setSplitMode("UNSUPPORTED");
            entry.getFieldSources().put("splitMode", "EXPLICIT");
            issue(entry, "splitMode", "UNSUPPORTED_SPLIT", null, List.of());
        } else if (!split.equals("EQUAL")) {
            entry.setSplitMode(split);
            issue(entry, "splitMode", "UNSUPPORTED_SPLIT", null, List.of());
        } else {
            entry.setSplitMode("EQUAL");
            entry.getFieldSources().put("splitMode", "DEFAULT");
        }
    }

    private boolean containsValidAmountEvidence(String source, String expression) {
        if (expression.isBlank()) return false;
        int start = source.indexOf(expression);
        while (start >= 0) {
            int end = start + expression.length();
            if (!isNonAmountNumber(source, start, end)) return true;
            start = source.indexOf(expression, start + 1);
        }
        return false;
    }

    private boolean isNonAmountNumber(String source, int start, int end) {
        var matcher = ISO_DATE_NUMBER.matcher(source);
        while (matcher.find()) {
            if (start < matcher.end() && end > matcher.start()) return true;
        }
        char before = start == 0 ? '\0' : source.charAt(start - 1);
        char after = end >= source.length() ? '\0' : source.charAt(end);
        String left = source.substring(Math.max(0, start - 4), start).stripTrailing();
        String right = source.substring(end, Math.min(source.length(), end + 4)).stripLeading();
        if ("第周星期号年月日时分秒".indexOf(before) >= 0 || "人个位次号年月日时分秒%％成比".indexOf(after) >= 0
                || (left.length() > 0 && "第周星期号年月日时分秒".indexOf(left.charAt(left.length() - 1)) >= 0)
                || (right.length() > 0 && "人个位次号年月日时分秒%％成比".indexOf(right.charAt(0)) >= 0)) return true;
        if ((before == ':' || before == '：' || before == '/') || (after == ':' || after == '：' || after == '/')) {
            return true;
        }
        return before == '第' || after == '第';
    }

    private boolean hasPayerEvidence(String source, String payerName,
                                     AiParsingContext.PersonCandidate payer,
                                     AiParsingContext context) {
        if (payerName.equals("我")) return source.contains("我") && rules.hasPaymentEvidence(source);
        for (String verb : PAYMENT_VERBS) {
            int verbAt = source.indexOf(verb);
            while (verbAt >= 0) {
                if (hasOnlyThisNamedPayerBefore(source, payer.name(), verbAt, context)) return true;
                verbAt = source.indexOf(verb, verbAt + verb.length());
            }
        }
        for (String pronoun : List.of("他", "她")) {
            int pronounAt = source.indexOf(pronoun);
            while (pronounAt >= 0) {
                for (String verb : PAYMENT_VERBS) {
                    int verbAt = source.indexOf(verb, pronounAt + pronoun.length());
                    if (verbAt >= 0 && verbAt - (pronounAt + pronoun.length()) <= 6
                            && uniqueEarlierPerson(source, pronounAt, context, payer.uuid())) return true;
                }
                pronounAt = source.indexOf(pronoun, pronounAt + pronoun.length());
            }
        }
        return false;
    }

    private boolean hasOnlyThisNamedPayerBefore(String source, String payerName, int verbStart,
                                                AiParsingContext context) {
        int nameAt = source.indexOf(payerName);
        if (nameAt < 0) return false;
        while (nameAt >= 0) {
            int nameEnd = nameAt + payerName.length();
            if (nameAt > verbStart || nameEnd > verbStart) {
                nameAt = source.indexOf(payerName, nameAt + payerName.length());
                continue;
            }
            int betweenStart = nameEnd;
            int betweenEnd = verbStart;
            String between = source.substring(betweenStart, betweenEnd);
            if (between.codePointCount(0, between.length()) > 40) {
                nameAt = source.indexOf(payerName, nameAt + payerName.length());
                continue;
            }
            boolean otherName = context.people().stream()
                    .filter(person -> !person.name().equals(payerName))
                    .anyMatch(person -> between.contains(person.name()));
            if (!otherName) return true;
            nameAt = source.indexOf(payerName, nameAt + payerName.length());
        }
        return false;
    }

    private boolean uniqueEarlierPerson(String source, int before, AiParsingContext context, String expectedUuid) {
        Set<String> mentioned = new LinkedHashSet<>();
        for (var person : context.people()) {
            int nameAt = source.indexOf(person.name());
            if (nameAt >= 0 && nameAt < before) mentioned.add(person.uuid());
        }
        return mentioned.size() == 1 && mentioned.contains(expectedUuid);
    }

    private boolean hasDirectNamedPaymentEvidence(String source, AiParsingContext context) {
        for (String verb : PAYMENT_VERBS) {
            int verbAt = source.indexOf(verb);
            while (verbAt >= 0) {
                int verbStart = verbAt;
                if (context.people().stream().anyMatch(person -> hasOnlyThisNamedPayerBefore(
                        source, person.name(), verbStart, context))) return true;
                verbAt = source.indexOf(verb, verbAt + verb.length());
            }
        }
        return false;
    }

    private boolean hasMultipleNamedPayers(String source, AiParsingContext context) {
        Set<String> mentionedNames = new LinkedHashSet<>();
        for (var person : context.people()) {
            if (source.contains(person.name())) mentionedNames.add(person.name());
        }
        if (mentionedNames.size() > 1 && containsAny(source, "各付", "各自付", "分别付", "分别支付",
                "分别垫付", "各自垫付", "分别代付", "各自代付", "分别付款", "各自付款")) {
            return true;
        }

        Set<String> payers = new LinkedHashSet<>();
        for (String verb : PAYMENT_VERBS) {
            int verbAt = source.indexOf(verb);
            while (verbAt >= 0) {
                int clauseStart = Math.max(Math.max(source.lastIndexOf('，', verbAt),
                                source.lastIndexOf(',', verbAt)),
                        Math.max(source.lastIndexOf('。', verbAt), source.lastIndexOf(';', verbAt)));
                String preceding = source.substring(Math.max(clauseStart + 1, verbAt - 32), verbAt);
                String nearestName = null;
                int nearestNameEnd = -1;
                for (var person : context.people()) {
                    int nameAt = preceding.lastIndexOf(person.name());
                    int nameEnd = nameAt < 0 ? -1 : nameAt + person.name().length();
                    if (nameEnd > nearestNameEnd) {
                        nearestName = person.name();
                        nearestNameEnd = nameEnd;
                    }
                }
                if (nearestName != null) payers.add(nearestName);
                if (payers.size() > 1) return true;
                verbAt = source.indexOf(verb, verbAt + verb.length());
            }
        }
        return false;
    }

    private static boolean hasExclusionEvidence(String source, String name) {
        if (source.contains("不包括" + name) || source.contains("不算" + name)
                || source.contains(name + "不承担") || source.contains(name + "不参与")) return true;
        int nameAt = source.indexOf(name);
        if (nameAt < 0) return false;
        for (String marker : List.of("除了", "除去", "不包括")) {
            int markerAt = source.lastIndexOf(marker, nameAt);
            if (markerAt >= 0) {
                String between = source.substring(markerAt + marker.length(), nameAt);
                if (between.length() <= 8 && !containsAny(between, "，", "。", ",", ";", "；")) return true;
            }
        }
        return false;
    }

    private static boolean hasMatchingClockTime(String source, LocalTime time) {
        String twoDigitHour = String.format("%02d", time.getHour());
        String hour = Integer.toString(time.getHour());
        String twoDigitMinute = String.format("%02d", time.getMinute());
        if (source.contains(twoDigitHour + ":" + twoDigitMinute)
                || source.contains(hour + ":" + twoDigitMinute)
                || source.contains(twoDigitHour + "：" + twoDigitMinute)
                || source.contains(hour + "：" + twoDigitMinute)) return true;
        return time.getMinute() == 0 && (source.contains(hour + "点") || source.contains(hour + "时"));
    }

    private static List<String> requiredTextList(JsonNode raw, String key, int maxItems, int maxLength) {
        JsonNode node = raw.get(key);
        if (node == null || !node.isArray() || node.size() > maxItems) throw invalid();
        var result = new ArrayList<String>();
        for (JsonNode value : node) {
            if (!value.isTextual()) throw invalid();
            String text = value.textValue().trim();
            if (text.isEmpty() || text.codePointCount(0, text.length()) > maxLength) throw invalid();
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static String requiredText(JsonNode raw, String key, int maxLength) {
        String value = requiredNullableText(raw, key, maxLength);
        if (value == null) throw invalid();
        return value;
    }

    private static String requiredNullableText(JsonNode raw, String key, int maxLength) {
        JsonNode node = raw.get(key);
        if (node == null) throw invalid();
        if (node.isNull()) return null;
        if (!node.isTextual()) throw invalid();
        String value = node.textValue().trim();
        if (value.codePointCount(0, value.length()) > maxLength) throw invalid();
        return value.isEmpty() ? null : value;
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private static Set<String> currenciesMentioned(String source) {
        var mentioned = new LinkedHashSet<String>();
        String upper = source.toUpperCase(Locale.ROOT);
        if (containsAny(source, "人民币", "元", "块", "块钱") || upper.contains("CNY")) mentioned.add("CNY");
        if (containsAny(source, "美元", "美金") || upper.contains("USD")) mentioned.add("USD");
        if (source.contains("欧元") || upper.contains("EUR")) mentioned.add("EUR");
        if (source.contains("英镑") || upper.contains("GBP")) mentioned.add("GBP");
        if (source.contains("日元") || upper.contains("JPY")) mentioned.add("JPY");
        if (source.contains("港币") || upper.contains("HKD")) mentioned.add("HKD");
        return mentioned;
    }

    private static void issue(AiDraftResp.Entry entry, String field, String code,
                              String source, List<String> candidates) {
        String id = field + ":" + code + ":" + (source == null ? "" : source);
        if (entry.getIssues().stream().noneMatch(existing -> existing.id().equals(id))) {
            entry.getIssues().add(new AiDraftResp.Issue(id, field, code, source, List.copyOf(candidates)));
        }
    }

    private static BusinessException amountMismatch() {
        return new BusinessException(ErrorCode.BAD_REQUEST, "金额与原文不一致，请用数字补充金额后重试");
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.BAD_REQUEST, "AI 返回的记账草稿无效，请重试或手动填写");
    }
}
