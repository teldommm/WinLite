package com.winlator.cmod.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One place that decides how driver / component versions are ordered in lists.
 *
 * <ul>
 *   <li>{@link #compareNatural}: text case-insensitively, runs of digits by value (R9 &lt; R10).
 *       Used for Adreno driver labels.</li>
 *   <li>{@link #compareNewestFirst}: for component names such as {@code 2.7.1-gplasync},
 *       {@code proton-3.0a}, {@code 2610}, {@code 0.4.4}. The first dotted number found in the
 *       name is the version and is compared numerically; the rest of the name only breaks ties.</li>
 * </ul>
 */
public final class VersionOrder {
    private VersionOrder() {}

    // A number that starts a word ("3.0a", "-2610"), not one glued to letters ("arm64ec", "x86_64").
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z0-9])\\d+(?:\\.\\d+)*");
    private static final Pattern ANY_NUMBER = Pattern.compile("\\d+(?:\\.\\d+)*");

    /** Text compared case-insensitively, runs of digits by their value: "R9" &lt; "R10". */
    public static int compareNatural(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i);
            char y = b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int startA = i;
                int startB = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String numA = stripLeadingZeros(a.substring(startA, i));
                String numB = stripLeadingZeros(b.substring(startB, j));
                if (numA.length() != numB.length()) return numA.length() < numB.length() ? -1 : 1;
                int byValue = numA.compareTo(numB);
                if (byValue != 0) return byValue;
            } else {
                char lx = Character.toLowerCase(x);
                char ly = Character.toLowerCase(y);
                if (lx != ly) return lx < ly ? -1 : 1;
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    /**
     * Newest version first. Names without any number go last. Equal versions keep a stable,
     * predictable order: the plain name before its suffixed variants ("2601" before
     * "2601-nightly").
     */
    public static int compareNewestFirst(String a, String b) {
        int[] va = versionOf(a);
        int[] vb = versionOf(b);
        boolean noneA = va.length == 0;
        boolean noneB = vb.length == 0;
        if (noneA != noneB) return noneA ? 1 : -1;
        int n = Math.max(va.length, vb.length);
        for (int k = 0; k < n; k++) {
            int x = k < va.length ? va[k] : 0;
            int y = k < vb.length ? vb[k] : 0;
            if (x != y) return x > y ? -1 : 1;
        }
        // Same version: the plain name first ("2601" before "2601-nightly"), otherwise the
        // later suffix first ("3.0b" before "3.0a").
        String la = a.toLowerCase();
        String lb = b.toLowerCase();
        if (la.startsWith(lb) || lb.startsWith(la)) return Integer.compare(a.length(), b.length());
        return compareNatural(b, a);
    }

    /**
     * The numeric version hidden in a component name; empty when there is none. Two naming
     * habits of the catalog are normalised first:
     * <ul>
     *   <li>{@code 036fix} means 0.3.6, so a leading "0" followed by two digits is split;</li>
     *   <li>FEXCore dates such as {@code 241214} (YYMMDD) compare as 2412.14, i.e. by month,
     *       so they sort below {@code 2505} instead of above everything.</li>
     * </ul>
     */
    static int[] versionOf(String name) {
        if (name == null) return new int[0];
        String text = name.replaceFirst("^(?i)(?:[a-z_-]*?)0(\\d)(\\d)(?![\\d.])", "0.$1.$2");
        Matcher m = NUMBER.matcher(text);
        if (!m.find()) {
            m = ANY_NUMBER.matcher(text);
            if (!m.find()) return new int[0];
        }
        String found = m.group();
        if (found.matches("\\d{6}")) found = found.substring(0, 4) + "." + found.substring(4);
        String[] parts = found.split("\\.");
        List<Integer> out = new ArrayList<>(parts.length);
        for (String part : parts) {
            try {
                out.add(Integer.parseInt(part.length() > 9 ? part.substring(0, 9) : part));
            } catch (NumberFormatException e) {
                out.add(0);
            }
        }
        int[] result = new int[out.size()];
        for (int i = 0; i < result.length; i++) result[i] = out.get(i);
        return result;
    }

    private static String stripLeadingZeros(String digits) {
        int start = 0;
        while (start < digits.length() - 1 && digits.charAt(start) == '0') start++;
        return digits.substring(start);
    }
}
