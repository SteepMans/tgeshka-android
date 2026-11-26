/*
 * This file is part of TGeshka, an unofficial client based on Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package org.telegram.tgeshka;

import java.util.ArrayList;
import java.util.List;

/**
 * Easter egg (пасхалка) in outgoing messages. The rule and the words live in
 * tgeshka-easter-egg.txt at the repository root, the same file in all three TGeshka
 * clients (copied into the assets). Plain Java without Android classes, so the unit
 * test runs with javac alone (TGeshkaEasterEggTest, Java compile job in build.yml).
 */
public final class TGeshkaEasterEgg {

    public static final class Rules {
        public final List<String> names = new ArrayList<>();
        public final List<String> fillers = new ArrayList<>();
        public final List<String> insults = new ArrayList<>();
        public final List<String[]> tests = new ArrayList<>();
        public String replacement = "";
        public int maxSuffix;
    }

    private TGeshkaEasterEgg() {
    }

    public static Rules parse(String content) {
        Rules rules = new Rules();
        String section = "";
        for (String raw : content.split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            if (line.charAt(0) == '[' && line.charAt(line.length() - 1) == ']') {
                section = line.substring(1, line.length() - 1);
            } else if (section.equals("names")) {
                rules.names.add(normalize(line));
            } else if (section.equals("fillers")) {
                rules.fillers.add(normalize(line));
            } else if (section.equals("insults")) {
                rules.insults.add(normalize(line));
            } else if (section.equals("replacement")) {
                rules.replacement = line;
            } else if (section.equals("max_suffix")) {
                rules.maxSuffix = 0;
                for (int i = 0; i < line.length(); i++) {
                    char c = line.charAt(i);
                    if (c >= '0' && c <= '9') {
                        rules.maxSuffix = rules.maxSuffix * 10 + (c - '0');
                    }
                }
            } else if (section.equals("tests")) {
                int arrow = line.indexOf(" => ");
                if (arrow >= 0) {
                    rules.tests.add(new String[]{line.substring(0, arrow), line.substring(arrow + 4)});
                }
            }
        }
        return rules;
    }

    /** The text with every match replaced, or the same object if nothing matched. */
    public static String apply(Rules rules, String text) {
        if (rules == null || text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder result = null;
        int copied = 0;
        int position = 0;
        while (position < text.length()) {
            if (!isWordChar(text.charAt(position))) {
                position++;
                continue;
            }
            int end = wordEnd(text, position);
            int tail = rules.names.contains(wordAt(text, position, end)) ? matchTail(rules, text, end) : 0;
            if (tail == 0) {
                position = end;
                continue;
            }
            if (result == null) {
                result = new StringBuilder();
            }
            result.append(text, copied, position);
            result.append(isUpper(text.charAt(position)) ? "Костя " : "костя ");
            result.append(rules.replacement);
            copied = position = tail;
        }
        if (result == null) {
            return text;
        }
        result.append(text, copied, text.length());
        return result.toString();
    }

    private static int matchTail(Rules rules, String text, int from) {
        int position = skipSeparators(text, from);
        int end = wordEnd(text, position);
        if (end > position && rules.fillers.contains(wordAt(text, position, end))) {
            position = skipSeparators(text, end);
            end = wordEnd(text, position);
        }
        int result = 0;
        while (end > position && isInsult(rules, wordAt(text, position, end))) {
            result = end;
            position = skipSeparators(text, end);
            end = wordEnd(text, position);
        }
        return result;
    }

    private static boolean isInsult(Rules rules, String word) {
        for (String root : rules.insults) {
            if (word.length() >= root.length() && word.length() - root.length() <= rules.maxSuffix && word.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    private static int wordEnd(String text, int from) {
        while (from < text.length() && isWordChar(text.charAt(from))) {
            from++;
        }
        return from;
    }

    private static int skipSeparators(String text, int from) {
        while (from < text.length() && isSeparator(text.charAt(from))) {
            from++;
        }
        return from;
    }

    private static String wordAt(String text, int from, int till) {
        return normalize(text.substring(from, till));
    }

    private static String normalize(String word) {
        StringBuilder result = new StringBuilder(word.length());
        for (int i = 0; i < word.length(); i++) {
            result.append(lower(word.charAt(i)));
        }
        return result.toString();
    }

    private static char lower(char c) {
        if ((c >= 'A' && c <= 'Z') || (c >= 'А' && c <= 'Я')) {
            return (char) (c + 32);
        } else if (c == 'Ё' || c == 'ё') {
            return 'е'; // ё is е
        }
        return c;
    }

    private static boolean isUpper(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'А' && c <= 'Я') || c == 'Ё';
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || (c >= 'Ѐ' && c <= 'ӿ');
    }

    private static boolean isSeparator(char c) {
        return c == ' ' || c == '\t' || c == ' ' || c == ',' || c == '-' || c == '–' || c == '—';
    }
}
