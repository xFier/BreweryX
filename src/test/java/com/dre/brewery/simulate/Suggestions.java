/*
 * BreweryX Bukkit-Plugin for an alternate brewing process
 * Copyright (C) 2024-2025 The Brewery Team
 *
 * This file is part of BreweryX.
 *
 * BreweryX is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BreweryX is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BreweryX. If not, see <http://www.gnu.org/licenses/gpl-3.0.html>.
 */

package com.dre.brewery.simulate;

import java.util.Collection;
import java.util.Locale;

/**
 * Turns a typo into a pointer at what the user probably meant.
 */
final class Suggestions {

    private Suggestions() {
    }

    /**
     * Describes an unrecognised key, naming the closest valid one when there is a plausible match.
     *
     * @param what  what kind of thing the key names, e.g. "key"
     * @param given what the user wrote
     * @param valid everything they could have written
     * @return a ready-to-print explanation
     */
    static String unknown(String what, String given, Collection<String> valid) {
        String closest = closest(given, valid);
        String known = "known " + what + "s: " + String.join(", ", valid);
        if (closest == null) {
            return "unknown " + what + " '" + given + "'; " + known;
        }
        return "unknown " + what + " '" + given + "'; did you mean '" + closest + "'? (" + known + ")";
    }

    /**
     * @return the candidate closest to {@code given}, or null if none is close enough to be worth
     * suggesting
     */
    static String closest(String given, Collection<String> candidates) {
        String lower = given.toLowerCase(Locale.ROOT);
        // Allow roughly a third of the word to be wrong before we stop guessing.
        int best = Math.max(1, lower.length() / 3 + 1);
        String bestCandidate = null;
        for (String candidate : candidates) {
            int distance = editDistance(lower, candidate.toLowerCase(Locale.ROOT));
            if (distance <= best) {
                best = distance;
                bestCandidate = candidate;
            }
        }
        return bestCandidate;
    }

    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

}
