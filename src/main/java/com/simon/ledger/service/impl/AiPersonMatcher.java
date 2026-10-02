package com.simon.ledger.service.impl;

import com.simon.ledger.dto.resp.AiDraftResp;
import com.simon.ledger.entity.LedgerPerson;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

import java.util.*;

/** Resolves identity only from authoritative ledger people. Suggestions never establish identity. */
public class AiPersonMatcher {
    public static List<LedgerPerson> activePeople(Long ledgerId, List<LedgerPerson> people) {
        Map<String, LedgerPerson> active = new LinkedHashMap<>();
        for (LedgerPerson person : people) {
            if (person.getDeletedAt() == null && Objects.equals(ledgerId, person.getLedgerId())
                    && person.getUuid() != null && !person.getUuid().isBlank()
                    && person.getName() != null && !normalize(person.getName()).isEmpty()) {
                active.putIfAbsent(person.getUuid(), person);
            }
        }
        return List.copyOf(active.values());
    }

    public AiDraftResp.PersonMatch match(String sourceName, String role, Long userId,
                                        List<LedgerPerson> active, List<String> suggestedNames) {
        String name = normalize(sourceName);
        AiDraftResp.PersonMatch result = new AiDraftResp.PersonMatch();
        result.setSourceName(sourceName);
        result.setRole(role);
        if ("我".equals(name)) {
            List<LedgerPerson> linked = active.stream()
                    .filter(person -> userId != null && userId.equals(person.getLinkedUserId())).toList();
            return select(result, linked, false);
        }
        List<LedgerPerson> exact = active.stream()
                .filter(person -> name.equals(normalize(person.getName()))).toList();
        if (!exact.isEmpty()) return select(result, exact, false);

        List<Set<String>> spokenSounds = sounds(name);
        List<LedgerPerson> homophones = active.stream()
                .filter(person -> overlaps(spokenSounds, sounds(normalize(person.getName())))).toList();
        if (homophones.size() == 1 && unambiguous(spokenSounds)
                && unambiguous(sounds(normalize(homophones.get(0).getName())))) {
            return select(result, homophones, true);
        }

        Set<String> suggestions = new HashSet<>();
        suggestedNames.forEach(value -> suggestions.add(normalize(value)));
        Set<String> candidates = new LinkedHashSet<>();
        for (LedgerPerson person : active) {
            String candidateName = normalize(person.getName());
            if (homophones.contains(person) || oneEditApart(name, candidateName)
                    || isAbbreviation(name, candidateName) || suggestions.contains(candidateName)) {
                candidates.add(person.getUuid());
            }
        }
        result.setCandidatePersonUuids(List.copyOf(candidates));
        result.setApproximate(!candidates.isEmpty());
        return result;
    }

    private AiDraftResp.PersonMatch select(AiDraftResp.PersonMatch result, List<LedgerPerson> matches,
                                          boolean approximate) {
        result.setCandidatePersonUuids(matches.stream().map(LedgerPerson::getUuid).toList());
        result.setApproximate(approximate);
        if (matches.size() == 1) {
            result.setPersonUuid(matches.get(0).getUuid());
            result.setMatchedName(matches.get(0).getName());
        }
        return result;
    }

    static String normalize(String name) {
        StringBuilder result = new StringBuilder();
        name.codePoints().forEach(codePoint -> {
            int value = codePoint >= 0xFF01 && codePoint <= 0xFF5E ? codePoint - 0xFEE0 : codePoint;
            if (!Character.isWhitespace(value) && !Character.isSpaceChar(value)) result.appendCodePoint(value);
        });
        return result.toString();
    }

    private List<Set<String>> sounds(String name) {
        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        format.setVCharType(HanyuPinyinVCharType.WITH_V);
        List<Set<String>> result = new ArrayList<>();
        for (int codePoint : name.codePoints().toArray()) {
            String[] pronunciations;
            try {
                pronunciations = codePoint <= Character.MAX_VALUE
                        ? PinyinHelper.toHanyuPinyinStringArray((char) codePoint, format) : null;
            } catch (BadHanyuPinyinOutputFormatCombination exception) {
                throw new IllegalStateException("Invalid internal pinyin format", exception);
            }
            Set<String> values = new HashSet<>();
            if (pronunciations != null) {
                for (String pronunciation : pronunciations) {
                    if (!pronunciation.startsWith("none")) values.add("p:" + pronunciation);
                }
            }
            // Unknown and non-Han code points match only themselves, never a fabricated pronunciation.
            if (values.isEmpty()) values.add("c:" + codePoint);
            result.add(values);
        }
        return result;
    }

    private boolean overlaps(List<Set<String>> left, List<Set<String>> right) {
        if (left.isEmpty() || left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            if (Collections.disjoint(left.get(i), right.get(i))) return false;
        }
        return true;
    }

    private boolean unambiguous(List<Set<String>> sounds) {
        return sounds.stream().allMatch(value -> value.size() == 1);
    }

    private boolean isAbbreviation(String source, String candidate) {
        int[] shortName = source.codePoints().toArray();
        int[] fullName = candidate.codePoints().toArray();
        if (shortName.length == 0 || shortName.length >= fullName.length) return false;
        int matched = 0;
        for (int value : fullName) {
            if (value == shortName[matched] && ++matched == shortName.length) return true;
        }
        return false;
    }

    private boolean oneEditApart(String left, String right) {
        int[] a = left.codePoints().toArray();
        int[] b = right.codePoints().toArray();
        if (a.length < 2 || b.length < 2) return false;
        if (Math.abs(a.length - b.length) > 1) return false;
        int i = 0, j = 0, edits = 0;
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue; }
            if (++edits > 1) return false;
            if (a.length >= b.length) i++;
            if (b.length >= a.length) j++;
        }
        return edits + (a.length - i) + (b.length - j) == 1;
    }
}
