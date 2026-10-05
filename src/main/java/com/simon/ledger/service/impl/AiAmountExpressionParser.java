package com.simon.ledger.service.impl;

import java.math.BigDecimal;
import java.util.Map;

public class AiAmountExpressionParser {
    private static final Map<Character, Integer> DIGITS = Map.ofEntries(
            Map.entry('零', 0), Map.entry('〇', 0), Map.entry('一', 1), Map.entry('二', 2),
            Map.entry('两', 2), Map.entry('三', 3), Map.entry('四', 4), Map.entry('五', 5),
            Map.entry('六', 6), Map.entry('七', 7), Map.entry('八', 8), Map.entry('九', 9));

    public BigDecimal parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Missing amount expression");
        }
        String value = expression.trim();
        if (value.matches("(?:[1-9]\\d{0,2}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,2})?")) {
            return new BigDecimal(value.replace(",", ""));
        }
        String[] parts = value.split("点", -1);
        if (parts.length > 2 || parts[0].isEmpty()) throw new IllegalArgumentException("Invalid Chinese amount");
        long integer = parseInteger(parts[0]);
        String fraction = "";
        if (parts.length == 2) {
            if (parts[1].isEmpty() || parts[1].length() > 2) {
                throw new IllegalArgumentException("Invalid decimal precision");
            }
            StringBuilder decimal = new StringBuilder();
            for (char digit : parts[1].toCharArray()) {
                if (!DIGITS.containsKey(digit)) throw new IllegalArgumentException("Invalid decimal digits");
                decimal.append(DIGITS.get(digit));
            }
            fraction = "." + decimal;
        }
        return new BigDecimal(Long.toString(integer) + fraction);
    }

    private static long parseInteger(String value) {
        if (value.chars().allMatch(codePoint -> DIGITS.containsKey((char) codePoint))) {
            StringBuilder literal = new StringBuilder();
            for (char digit : value.toCharArray()) literal.append(DIGITS.get(digit));
            return Long.parseLong(literal.toString());
        }
        long total = 0;
        long section = 0;
        long number = 0;
        int previousUnit = 10000;
        boolean seenWan = false;
        boolean previousWasNonZeroDigit = false;
        for (char character : value.toCharArray()) {
            if (DIGITS.containsKey(character)) {
                int digit = DIGITS.get(character);
                if (previousWasNonZeroDigit && digit != 0) {
                    throw new IllegalArgumentException("Invalid adjacent digits");
                }
                number = digit;
                previousWasNonZeroDigit = digit != 0;
                continue;
            }
            int unit = switch (character) {
                case '十' -> 10;
                case '百' -> 100;
                case '千' -> 1000;
                case '万' -> 10000;
                default -> 0;
            };
            if (unit == 0) throw new IllegalArgumentException("Unsupported amount expression");
            if (unit == 10000) {
                if (seenWan || section + number == 0) throw new IllegalArgumentException("Invalid ten-thousand unit");
                total += (section + number) * unit;
                section = 0;
                previousUnit = 10000;
                seenWan = true;
            } else {
                if (unit >= previousUnit || (number == 0 && !(unit == 10 && section == 0))) {
                    throw new IllegalArgumentException("Invalid Chinese unit order");
                }
                section += (number == 0 ? 1 : number) * unit;
                previousUnit = unit;
            }
            number = 0;
            previousWasNonZeroDigit = false;
        }
        return total + section + number;
    }
}
