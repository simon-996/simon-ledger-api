package com.simon.ledger.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.dto.resp.AiDraftResp;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerPerson;
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
            int type = raw.path("type").asInt(-1);
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
            entry.setCategorySuggestion(optionalText(raw, "categorySuggestion", 64));
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
                    resolve(optionalNodeText(name, 64), userId, people, personUuids,
                            entry.getUnresolvedNames());
                }
            }
            entry.setPersonUuids(List.copyOf(personUuids));
            String payerName = optionalText(raw, "payerName", 64);
            if (payerName != null) {
                Set<String> payer = new LinkedHashSet<>();
                resolve(payerName, userId, people, payer, entry.getUnresolvedNames());
                if (payer.size() == 1) {
                    entry.setPayerPersonUuid(payer.iterator().next());
                }
            }
            response.getEntries().add(entry);
        }
        return response;
    }

    private void resolve(String name, Long userId, List<LedgerPerson> people, Set<String> resolved,
                         List<String> unresolved) {
        if (name == null) {
            throw invalid();
        }
        List<LedgerPerson> matches = people.stream()
                .filter(person -> person.getDeletedAt() == null)
                .filter(person -> "我".equals(name)
                        ? userId.equals(person.getLinkedUserId()) : name.equals(person.getName()))
                .toList();
        if (matches.size() == 1) {
            resolved.add(matches.get(0).getUuid());
        } else if (!unresolved.contains(name)) {
            unresolved.add(name);
        }
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
