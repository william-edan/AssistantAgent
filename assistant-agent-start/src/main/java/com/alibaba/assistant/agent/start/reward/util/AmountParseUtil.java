/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.assistant.agent.start.reward.util;

import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 奖惩金额解析工具。
 */
public final class AmountParseUtil {

    private static final Pattern ARABIC_WITH_UNIT =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:元|块钱|块|人民币)");

    private static final Pattern ARABIC_AFTER_ACTION =
            Pattern.compile("(?:奖励|发|罚|惩罚)\\s*(\\d+(?:\\.\\d+)?)");

    private static final Pattern CHINESE_WITH_UNIT =
            Pattern.compile("([零一二三四五六七八九十百千万两]+)\\s*(?:元|块钱|块|人民币)");

    private static final Pattern CHINESE_AFTER_ACTION =
            Pattern.compile("(?:奖励|发|罚|惩罚)\\s*([零一二三四五六七八九十百千万两]+)");

    private static final Map<Character, Integer> DIGIT_MAP = createDigitMap();

    private static final Map<Character, Integer> UNIT_MAP = createUnitMap();

    private AmountParseUtil() {
    }

    public static Optional<BigDecimal> parse(String text) {
        if (!StringUtils.hasText(text)) {
            return Optional.empty();
        }
        return parseArabic(text)
                .or(() -> parseChinese(text));
    }

    private static Optional<BigDecimal> parseArabic(String text) {
        return findNumber(text, ARABIC_WITH_UNIT)
                .or(() -> findNumber(text, ARABIC_AFTER_ACTION));
    }

    private static Optional<BigDecimal> parseChinese(String text) {
        return findChineseNumber(text, CHINESE_WITH_UNIT)
                .or(() -> findChineseNumberAfterAction(text));
    }

    private static Optional<BigDecimal> findNumber(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(new BigDecimal(matcher.group(1)));
    }

    private static Optional<BigDecimal> findChineseNumber(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(BigDecimal.valueOf(parseChineseNumberValue(matcher.group(1))));
    }

    private static Optional<BigDecimal> findChineseNumberAfterAction(String text) {
        Matcher matcher = CHINESE_AFTER_ACTION.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String candidate = matcher.group(1);
        if (!containsUnit(candidate) && candidate.length() < 2) {
            return Optional.empty();
        }
        return Optional.of(BigDecimal.valueOf(parseChineseNumberValue(candidate)));
    }

    // 中文数字需要按单位累加，否则“两百”“一千零五”这类表达无法直接转成阿拉伯数字。
    private static long parseChineseNumberValue(String text) {
        long result = 0;
        long section = 0;
        long number = 0;
        for (char current : text.toCharArray()) {
            Integer digit = DIGIT_MAP.get(current);
            if (digit != null) {
                number = digit;
                continue;
            }
            Integer unit = UNIT_MAP.get(current);
            if (unit == null) {
                continue;
            }
            if (unit == 10 || unit == 100 || unit == 1000) {
                if (number == 0) {
                    number = 1;
                }
                section += number * unit;
            }
            else {
                section = (section + number) * unit;
                result += section;
                section = 0;
            }
            number = 0;
        }
        return result + section + number;
    }

    private static boolean containsUnit(String text) {
        return text.indexOf('十') >= 0
                || text.indexOf('百') >= 0
                || text.indexOf('千') >= 0
                || text.indexOf('万') >= 0;
    }

    private static Map<Character, Integer> createDigitMap() {
        Map<Character, Integer> digits = new LinkedHashMap<>();
        digits.put('零', 0);
        digits.put('一', 1);
        digits.put('二', 2);
        digits.put('三', 3);
        digits.put('四', 4);
        digits.put('五', 5);
        digits.put('六', 6);
        digits.put('七', 7);
        digits.put('八', 8);
        digits.put('九', 9);
        digits.put('两', 2);
        return digits;
    }

    private static Map<Character, Integer> createUnitMap() {
        Map<Character, Integer> units = new LinkedHashMap<>();
        units.put('十', 10);
        units.put('百', 100);
        units.put('千', 1000);
        units.put('万', 10000);
        return units;
    }
}
