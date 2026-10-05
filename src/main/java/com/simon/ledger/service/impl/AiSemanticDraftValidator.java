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
    private static final Pattern CLOCK_TIME = Pattern.compile("(?<!\\d)(\\d{1,2})[:：](\\d{1,2})(?!\\d)");
    private static final Pattern CHINESE_TIME = Pattern.compile(
            "(?<!\\d)(\\d{1,2})[点时](?:(\\d{1,2})分?)?(?![\\d半零〇一二两三四五六七八九十])");
    private static final Pattern TIME_OF_DAY = Pattern.compile("凌晨|早上|上午|中午|下午|晚上");
    private static final Pattern NEGATED_PAYMENT = Pattern.compile(
            "(?:没(?:有)?|未(?:曾)?|不(?:是)?|并非)(?:垫|代|先|支)?\\s*$");
    private static final Map<String, List<String>> FOREIGN_CURRENCY_NAMES = Map.ofEntries(
            Map.entry("USD", List.of("美元", "美金")), Map.entry("EUR", List.of("欧元")),
            Map.entry("GBP", List.of("英镑")), Map.entry("JPY", List.of("日元", "日币")),
            Map.entry("HKD", List.of("港元", "港币")), Map.entry("TWD", List.of("新台币", "台币")),
            Map.entry("MOP", List.of("澳门元", "澳门币")), Map.entry("SGD", List.of("新加坡元", "新币")),
            Map.entry("THB", List.of("泰铢")), Map.entry("MYR", List.of("马来西亚林吉特", "林吉特", "马币")),
            Map.entry("KRW", List.of("韩元", "韩币")), Map.entry("AUD", List.of("澳元", "澳币")),
            Map.entry("CAD", List.of("加元", "加币")), Map.entry("NZD", List.of("新西兰元", "纽元", "纽币")),
            Map.entry("CHF", List.of("瑞士法郎")));
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
        int sourceCursor = 0;
        for (JsonNode raw : entries) {
            AiDraftResp.Entry entry = validateEntry(raw, context);
            int sourceStart = context.text().indexOf(entry.getSourceText(), sourceCursor);
            if (sourceStart < 0) throw invalid();
            sourceCursor = sourceStart + entry.getSourceText().length();
            result.getEntries().add(entry);
        }
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
            if (CLOCK_TIME.matcher(source).find() || CHINESE_TIME.matcher(source).find()) {
                issue(entry, "happenedAt", "DATE_AMBIGUOUS", null, List.of());
            }
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
            boolean explicitClock = hasMatchingClockTime(source, time);
            if ((!time.equals(LocalTime.MIDNIGHT) || rules.hasTimeEvidence(source)) && !explicitClock) {
                issue(entry, "happenedAt", "DATE_AMBIGUOUS", happenedAtValue, List.of());
                entry.setHappenedAt(day.atStartOfDay());
                entry.setDatePrecision("DAY");
                return;
            }
            entry.setHappenedAt(dateTime);
            entry.setDatePrecision(explicitClock ? "TIME" : "DAY");
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
        if (isNumeralCharacter(before) || isNumeralCharacter(after) || before == '.'
                || (after == '.' && end + 1 < source.length() && Character.isDigit(source.charAt(end + 1)))
                || (before == ',' && start > 1 && Character.isDigit(source.charAt(start - 2)))
                || (after == ',' && end + 1 < source.length() && Character.isDigit(source.charAt(end + 1)))) {
            return true;
        }
        String left = source.substring(Math.max(0, start - 4), start).stripTrailing();
        String right = source.substring(end, Math.min(source.length(), end + 4)).stripLeading();
        boolean japaneseCurrency = FOREIGN_CURRENCY_NAMES.get("JPY").stream().anyMatch(right::startsWith);
        if ("第周星期号年月日时分秒".indexOf(before) >= 0
                || (!japaneseCurrency && "人个位次号年月日时分秒%％成比".indexOf(after) >= 0)
                || (left.length() > 0 && "第周星期号年月日时分秒".indexOf(left.charAt(left.length() - 1)) >= 0)
                || (!japaneseCurrency && right.length() > 0
                && "人个位次号年月日时分秒%％成比".indexOf(right.charAt(0)) >= 0)) return true;
        if ((before == ':' || before == '：' || before == '/') || (after == ':' || after == '：' || after == '/')) {
            return true;
        }
        return before == '第' || after == '第';
    }

    private static boolean isNumeralCharacter(char value) {
        return Character.isDigit(value) || "零〇一二两三四五六七八九十百千万点".indexOf(value) >= 0;
    }

    private boolean hasPayerEvidence(String source, String payerName,
                                     AiParsingContext.PersonCandidate payer,
                                     AiParsingContext context) {
        String directName = payerName.equals("我") ? "我" : payer.name();
        for (String verb : PAYMENT_VERBS) {
            int verbAt = source.indexOf(verb);
            while (verbAt >= 0) {
                if (hasOnlyThisNamedPayerBefore(source, directName, verbAt, context)) return true;
                verbAt = source.indexOf(verb, verbAt + verb.length());
            }
        }
        if (payerName.equals("我")) return false;
        for (String pronoun : List.of("他", "她")) {
            int pronounAt = source.indexOf(pronoun);
            while (pronounAt >= 0) {
                for (String verb : PAYMENT_VERBS) {
                    int verbAt = source.indexOf(verb, pronounAt + pronoun.length());
                    if (verbAt >= 0 && verbAt - (pronounAt + pronoun.length()) <= 6
                            && !isNegatedPayment(source, verbAt)
                            && uniqueEarlierPerson(source, pronounAt, context, payer.uuid())) return true;
                }
                pronounAt = source.indexOf(pronoun, pronounAt + pronoun.length());
            }
        }
        return false;
    }

    private boolean hasOnlyThisNamedPayerBefore(String source, String payerName, int verbStart,
                                                AiParsingContext context) {
        if (isNegatedPayment(source, verbStart)) return false;
        int nameAt = source.indexOf(payerName);
        if (nameAt < 0) return false;
        while (nameAt >= 0) {
            int nameEnd = nameAt + payerName.length();
            String beforeName = source.substring(Math.max(0, nameAt - 4), nameAt);
            if ((payerName.equals("我") && nameEnd < source.length() && source.charAt(nameEnd) == '们')
                    || beforeName.endsWith("不是") || beforeName.endsWith("并非") || beforeName.endsWith("不由")) {
                nameAt = source.indexOf(payerName, nameEnd);
                continue;
            }
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

    private static boolean isNegatedPayment(String source, int verbStart) {
        return NEGATED_PAYMENT.matcher(source.substring(Math.max(0, verbStart - 8), verbStart)).find();
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
                if (isNegatedPayment(source, verbAt)) {
                    verbAt = source.indexOf(verb, verbAt + verb.length());
                    continue;
                }
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
        if (time.getSecond() != 0 || time.getNano() != 0) return false;
        for (Pattern pattern : List.of(CLOCK_TIME, CHINESE_TIME)) {
            var matcher = pattern.matcher(source);
            while (matcher.find()) {
                int hour = Integer.parseInt(matcher.group(1));
                int minute = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
                if (hour > 23 || minute > 59) continue;
                String prefix = source.substring(Math.max(0, matcher.start() - 8), matcher.start());
                var periodMatcher = TIME_OF_DAY.matcher(prefix);
                String period = "";
                while (periodMatcher.find()) period = periodMatcher.group();
                if ((period.equals("下午") || period.equals("晚上")) && hour < 12) hour += 12;
                if (period.equals("中午") && hour >= 1 && hour <= 6) hour += 12;
                if (period.equals("凌晨") && hour == 12) hour = 0;
                if (hour == 12 && (period.equals("晚上") || period.equals("上午") || period.equals("早上"))) {
                    continue;
                }
                if (time.getHour() == hour && time.getMinute() == minute) return true;
            }
        }
        return false;
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
        String domesticSource = source;
        for (var currency : FOREIGN_CURRENCY_NAMES.entrySet()) {
            if (currency.getValue().stream().anyMatch(source::contains)
                    || hasCurrencyCode(upper, currency.getKey())) mentioned.add(currency.getKey());
            for (String name : currency.getValue()) domesticSource = domesticSource.replace(name, "");
        }
        if (containsAny(domesticSource, "人民币", "元", "块", "块钱") || hasCurrencyCode(upper, "CNY")) {
            mentioned.add("CNY");
        }
        return mentioned;
    }

    private static boolean hasCurrencyCode(String source, String code) {
        return Pattern.compile("(?<![A-Z])" + code + "(?![A-Z])").matcher(source).find();
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
