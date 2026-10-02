package com.simon.ledger.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.dto.resp.AiDraftResp;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerPerson;
import com.simon.ledger.infrastructure.ai.AiCategoryContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class AiDraftValidator {
    private final ObjectMapper mapper;

    public AiDraftResp validate(String providerJson, Ledger ledger, Long userId, ZoneId zone,
                                List<LedgerPerson> people) {
        return validate(providerJson, ledger, userId, zone, people, AiCategoryContext.defaults());
    }

    public AiDraftResp validate(String providerJson, Ledger ledger, Long userId, ZoneId zone,
                                List<LedgerPerson> people, AiCategoryContext categories) {
        List<LedgerPerson> active = AiPersonMatcher.activePeople(ledger.getId(), people);
        AiPersonMatcher matcher = new AiPersonMatcher();
        final JsonNode root;
        try {
            root = mapper.readTree(providerJson);
        } catch (Exception exception) {
            throw invalid();
        }
        JsonNode entries = root == null ? null : root.path("entries");
        if (entries == null || !entries.isArray() || entries.isEmpty() || entries.size() > 10) {
            throw invalid();
        }
        AiDraftResp response = new AiDraftResp();
        for (JsonNode raw : entries) {
            AiDraftResp.Entry entry = new AiDraftResp.Entry();
            JsonNode typeNode = raw.path("type");
            if (!typeNode.isIntegralNumber() || !typeNode.canConvertToInt()) throw invalid();
            int type = typeNode.intValue();
            if (type != 0 && type != 1) {
                throw invalid();
            }
            entry.setType(type);
            BigDecimal amount;
            try {
                amount = new BigDecimal(requiredText(raw, "amount", 32)).setScale(2);
            } catch (Exception exception) {
                throw invalid();
            }
            if (amount.signum() <= 0 || amount.precision() > 12) {
                throw invalid();
            }
            entry.setAmount(amount);
            String currency = requiredText(raw, "currencyCode", 3).toUpperCase();
            try {
                Currency.getInstance(currency);
            } catch (IllegalArgumentException exception) {
                throw invalid();
            }
            entry.setCurrencyCode(currency);
            entry.setSourceText(optionalText(raw, "sourceText", 1000));
            JsonNode category = raw.path("categorySuggestion");
            entry.setCategorySuggestion(categories.retain(type, category.isTextual() ? category.textValue().strip() : null));
            entry.setNote(optionalText(raw, "note", 512));
            String date = optionalText(raw, "happenedAt", 40);
            if (date != null) {
                try {
                    LocalDateTime value = LocalDateTime.parse(date);
                    if (zone.getRules().getValidOffsets(value).isEmpty()) {
                        throw invalid();
                    }
                    entry.setHappenedAt(value);
                } catch (DateTimeException exception) {
                    throw invalid();
                }
            }
            Set<String> personUuids = new LinkedHashSet<>();
            JsonNode names = raw.path("personNames");
            if (!names.isMissingNode() && !names.isNull()) {
                if (!names.isArray() || names.size() > 30) {
                    throw invalid();
                }
                for (JsonNode name : names) {
                    String sourceName = originalName(name);
                    if (sourceName == null) throw invalid();
                    var match = matcher.match(sourceName, "participant", userId, active,
                            suggestedNames(raw, sourceName, "participant"));
                    addMatch(entry, match);
                    if (match.getPersonUuid() != null) personUuids.add(match.getPersonUuid());
                }
            }
            entry.setPersonUuids(List.copyOf(personUuids));
            JsonNode payerNode = raw.path("payerName");
            String payerName = payerNode.isMissingNode() || payerNode.isNull() ? null : originalName(payerNode);
            if (payerName != null) {
                var match = matcher.match(payerName, "payer", userId, active,
                        suggestedNames(raw, payerName, "payer"));
                addMatch(entry, match);
                entry.setPayerPersonUuid(match.getPersonUuid());
            }
            String paymentMode = optionalText(raw, "paymentMode", 64);
            entry.setPaymentMode(entry.getPayerPersonUuid() != null ? "person"
                    : "shared_wallet".equals(paymentMode) && payerName == null ? "shared_wallet" : "unconfirmed");
            response.getEntries().add(entry);
        }
        return response;
    }

    private void addMatch(AiDraftResp.Entry entry, AiDraftResp.PersonMatch match) {
        entry.getPersonMatches().add(match);
        if (match.getPersonUuid() == null && !entry.getUnresolvedNames().contains(match.getSourceName())) {
            entry.getUnresolvedNames().add(match.getSourceName());
        }
    }

    private String originalName(JsonNode node) {
        if (!node.isTextual()) throw invalid();
        String name = node.textValue();
        if (name.codePointCount(0, name.length()) > 64) throw invalid();
        return AiPersonMatcher.normalize(name).isEmpty() ? null : name;
    }

    private List<String> suggestedNames(JsonNode raw, String sourceName, String role) {
        JsonNode suggestions = raw.path("personSuggestions");
        if (suggestions.isMissingNode() || suggestions.isNull()) return List.of();
        if (!suggestions.isArray() || suggestions.size() > 60) throw invalid();
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode suggestion : suggestions) {
            String source = originalName(suggestion.path("sourceName"));
            String suggestedRole = requiredText(suggestion, "role", 16);
            if (!"participant".equals(suggestedRole) && !"payer".equals(suggestedRole)) throw invalid();
            JsonNode candidates = suggestion.path("candidateNames");
            if (!candidates.isArray() || candidates.size() > 30) throw invalid();
            for (JsonNode candidate : candidates) {
                String name = originalName(candidate);
                if (sourceName.equals(source) && role.equals(suggestedRole) && name != null) names.add(name);
            }
        }
        return List.copyOf(names);
    }

    private String requiredText(JsonNode raw, String key, int maxLength) {
        String text = optionalText(raw, key, maxLength);
        if (text == null) {
            throw invalid();
        }
        return text;
    }

    private String optionalText(JsonNode raw, String key, int maxLength) {
        JsonNode node = raw.path(key);
        return node.isMissingNode() || node.isNull() ? null : optionalNodeText(node, maxLength);
    }

    private String optionalNodeText(JsonNode node, int maxLength) {
        if (!node.isTextual()) {
            throw invalid();
        }
        String text = node.textValue().trim();
        if (text.codePointCount(0, text.length()) > maxLength) {
            throw invalid();
        }
        return text.isEmpty() ? null : text;
    }

    private BusinessException invalid() {
        return new BusinessException(ErrorCode.BAD_REQUEST, "AI 返回的记账草稿无效，请重试或手动填写");
    }
}
