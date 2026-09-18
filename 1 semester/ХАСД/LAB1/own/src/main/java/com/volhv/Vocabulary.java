package com.volhv;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Vocabulary {
    static final int MAX_TOKENS = 1 << 20;
    static final int MIN_COUNT = 3;
    static final int PRUNE_AT = 5_000_000;

    final int[] chars;
    final String[] tokens;
    final Map<Integer, Integer> charCode = new HashMap<>();
    final Map<String, Integer> tokenCode = new HashMap<>();

    Vocabulary(int[] chars, String[] tokens) {
        this.chars = chars;
        this.tokens = tokens;
        for (int i = 0; i < chars.length; i++) charCode.put(chars[i], i);
        for (int i = 0; i < tokens.length; i++) tokenCode.put(tokens[i], i + 2);
    }

    static List<String> tokenize(String s) {
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            int j = i;
            if (Character.isLetterOrDigit(s.charAt(i))) {
                while (j < s.length() && Character.isLetterOrDigit(s.charAt(j))) j++;
            } else {
                j += Character.charCount(s.codePointAt(i));
            }
            if (j < s.length() && s.charAt(j) == ' ') j++;
            result.add(s.substring(i, j));
            i = j;
        }
        return result;
    }

    static class Counter {
        final Map<String, Integer> tokens = new HashMap<>();
        final long[] chars = new long[Character.MAX_CODE_POINT + 1];
        int limit = PRUNE_AT;

        void addTokens(String s) {
            for (String t : tokenize(s)) tokens.merge(t, 1, Integer::sum);
            if (tokens.size() > limit) {
                tokens.values().removeIf(c -> c == 1);
                limit = Math.max(PRUNE_AT, tokens.size() * 2);
            }
        }

        void addChars(String s) {
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                chars[cp]++;
                i += Character.charCount(cp);
            }
        }

        Vocabulary build() {
            List<Map.Entry<String, Integer>> entries = new ArrayList<>();
            for (Map.Entry<String, Integer> e : tokens.entrySet()) {
                if (e.getValue() >= MIN_COUNT) entries.add(e);
            }
            entries.sort((a, b) -> b.getValue() - a.getValue());
            String[] sortedTokens = new String[Math.min(entries.size(), MAX_TOKENS)];
            for (int i = 0; i < sortedTokens.length; i++) sortedTokens[i] = entries.get(i).getKey();

            List<Integer> codePoints = new ArrayList<>();
            for (int cp = 0; cp < chars.length; cp++) {
                if (chars[cp] > 0) codePoints.add(cp);
            }
            codePoints.sort((a, b) -> Long.compare(chars[b], chars[a]));
            return new Vocabulary(codePoints.stream().mapToInt(Integer::intValue).toArray(), sortedTokens);
        }
    }
}
