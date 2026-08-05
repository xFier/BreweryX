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

import com.dre.brewery.BarrelWoodType;
import com.dre.brewery.Brew;
import com.dre.brewery.BrewDefect;
import com.dre.brewery.recipe.BCauldronRecipe;
import com.dre.brewery.recipe.BEffect;
import com.dre.brewery.recipe.BRecipe;
import com.dre.brewery.recipe.QualityDeduction;
import com.dre.brewery.recipe.RecipeEvaluation;
import org.bukkit.ChatColor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares what a brew actually came out as against what a case said it should be.
 * <p>
 * Every supported key is declared once in {@link #KEYS}; the validation messages and the
 * comparisons both read from there, so adding a key means touching this file only.
 */
public final class BrewExpectation {

    /** Every key an `expect:` block may carry, in the order they are documented. */
    public static final List<String> KEYS = List.of(
        "recipe", "notRecipe", "cauldronRecipe", "notCauldronRecipe", "quality", "alcohol", "distillRuns",
        "ageTime", "wood", "hasRecipe", "effects", "defects", "worstDefect", "outcome");

    /**
     * Keys that mean something even when nothing came out of the cauldron. Everything else needs a
     * brew to compare against, so its absence is itself the mismatch.
     */
    private static final Set<String> KEYS_WITHOUT_A_BREW =
        Set.of("outcome", "cauldronRecipe", "notCauldronRecipe", "notRecipe");

    private static final Pattern RANGE = Pattern.compile(
        "\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\.\\.\\s*(-?\\d+(?:\\.\\d+)?)\\s*");

    private BrewExpectation() {
    }

    /**
     * An inclusive numeric range, written {@code min..max}.
     */
    private record Range(double min, double max) {
        /**
         * Compares at float precision, because every number a brew reports is a float or an int.
         * A spec asking for {@code 0.3} has to match a brew holding {@code 0.3f}, which widens to
         * 0.30000001192 and would otherwise never equal the 0.29999999999 that YAML parsed.
         */
        boolean matches(double value) {
            return (float) value >= (float) min && (float) value <= (float) max;
        }

        @Override
        public String toString() {
            return min == max ? trim(min) : trim(min) + ".." + trim(max);
        }
    }

    /**
     * Checks that an `expect:` block only uses known keys with plausible values.
     *
     * @param raw      the block as YAML gave it to us
     * @param where    how to refer to the owning case in messages
     * @param problems collects anything wrong
     * @return the block, with keys normalised to strings
     */
    static Map<String, Object> validate(Map<?, ?> raw, String where, List<String> problems) {
        Map<String, Object> expect = new LinkedHashMap<>();

        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();

            if (!KEYS.contains(key)) {
                problems.add(where + ": in `expect`, " + Suggestions.unknown("key", key, KEYS));
                continue;
            }
            expect.put(key, value);

            switch (key) {
                case "quality", "alcohol", "ageTime" -> {
                    if (asRange(value) == null) {
                        problems.add(where + ": `expect." + key
                            + "` should be a number or a range like `8..10`, but is " + BrewSpec.describe(value));
                    }
                }
                case "distillRuns" -> {
                    if (!(value instanceof Number)) {
                        problems.add(where + ": `expect.distillRuns` should be a whole number, but is "
                            + BrewSpec.describe(value));
                    }
                }
                case "hasRecipe" -> {
                    if (!(value instanceof Boolean)) {
                        problems.add(where + ": `expect.hasRecipe` should be true or false, but is "
                            + BrewSpec.describe(value));
                    }
                }
                case "recipe", "cauldronRecipe" -> {
                    // null is meaningful here: "no recipe matched"
                    if (value != null && !(value instanceof String)) {
                        problems.add(where + ": `expect." + key
                            + "` should be a name, or empty to mean none matched, but is " + BrewSpec.describe(value));
                    }
                }
                case "notRecipe", "notCauldronRecipe" -> {
                    // Unlike `recipe`, empty says nothing: "not none" is not an assertion.
                    if (!(value instanceof String)) {
                        problems.add(where + ": `expect." + key + "` should be the name of a recipe that must "
                            + "not have matched, but is " + BrewSpec.describe(value));
                    }
                }
                case "wood" -> validateEnum(value, where, key, woodNames(), problems);
                case "outcome" -> validateEnum(value, where, key, outcomeNames(), problems);
                case "worstDefect" -> {
                    if (!(value instanceof String)) {
                        problems.add(where + ": `expect.worstDefect` should be a defect like "
                            + "`WrongCount{4/3 WHEAT}`, but is " + BrewSpec.describe(value));
                    }
                }
                case "effects", "defects" -> {
                    if (!(value instanceof List<?> list)) {
                        problems.add(where + ": `expect." + key + "` should be a list, but is "
                            + BrewSpec.describe(value));
                    } else {
                        for (Object element : list) {
                            if (!(element instanceof String)) {
                                problems.add(where + ": every entry of `expect." + key + "` should be text, but one is "
                                    + BrewSpec.describe(element));
                            }
                        }
                    }
                }
                default -> throw new IllegalStateException("Unhandled expectation key: " + key);
            }
        }
        return expect;
    }

    private static void validateEnum(@Nullable Object value, String where, String key,
                                     Set<String> allowed, List<String> problems) {
        if (!(value instanceof String text)) {
            problems.add(where + ": `expect." + key + "` should be one of " + String.join(", ", allowed)
                + ", but is " + BrewSpec.describe(value));
            return;
        }
        if (allowed.stream().noneMatch(a -> a.equalsIgnoreCase(text))) {
            problems.add(where + ": in `expect." + key + "`, " + Suggestions.unknown("value", text, allowed));
        }
    }

    /**
     * Compares a brewing result against a case's expectations.
     *
     * @param result what came out of the simulation
     * @param expect what the case said should come out
     * @return one line per mismatch; empty if the brew is as declared
     */
    public static List<String> check(SimulationResult result, Map<String, Object> expect) {
        List<String> mismatches = new ArrayList<>();
        Brew brew = result.brew();

        for (Map.Entry<String, Object> entry : expect.entrySet()) {
            String key = entry.getKey();
            Object expected = entry.getValue();

            if (brew == null && !KEYS_WITHOUT_A_BREW.contains(key)) {
                mismatches.add(key + ": no brew was produced at all (outcome " + result.outcome() + ")");
                continue;
            }

            switch (key) {
                case "recipe" -> checkName(mismatches, key, expected, recipeNames(brew.getCurrentRecipe()));
                case "cauldronRecipe" -> checkName(mismatches, key, expected, cauldronNames(result.cauldronRecipe()));
                // Nothing at all came out is the strongest possible way of not being this recipe.
                case "notRecipe" -> checkNotName(mismatches, key, expected,
                    brew == null ? Set.of() : recipeNames(brew.getCurrentRecipe()));
                case "notCauldronRecipe" -> checkNotName(mismatches, key, expected,
                    cauldronNames(result.cauldronRecipe()));
                case "quality" -> checkNumber(mismatches, key, expected, brew.getQuality());
                case "alcohol" -> checkNumber(mismatches, key, expected, brew.getAlc());
                case "ageTime" -> checkNumber(mismatches, key, expected, brew.getAgeTime());
                case "distillRuns" -> checkNumber(mismatches, key, expected, brew.getDistillRuns());
                case "wood" -> checkEquals(mismatches, key, expected, brew.getWood().name());
                case "hasRecipe" -> checkEquals(mismatches, key, expected, brew.hasRecipe());
                case "outcome" -> checkEquals(mismatches, key, expected, result.outcome().name());
                case "effects" -> checkSet(mismatches, key, expected, effectNames(brew));
                case "defects" -> checkSet(mismatches, key, expected, defectNames(result.evaluation()));
                case "worstDefect" -> checkAnyOf(mismatches, key, expected, worstDefectNames(result.evaluation()));
                default -> throw new IllegalStateException("Unhandled expectation key: " + key);
            }
        }
        return mismatches;
    }

    /**
     * Everything about the result worth showing when a case fails.
     */
    public static String describe(SimulationResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("outcome: ").append(result.outcome());
        if (result.brew() != null) {
            sb.append("\nbrew:    ").append(ChatColor.stripColor(result.brew().toString()));
        }
        if (result.cauldronRecipe() != null) {
            sb.append("\nferment: ").append(stripColor(result.cauldronRecipe().getName()))
                .append(" (id ").append(result.cauldronRecipe().getId()).append(')');
        }

        RecipeEvaluation evaluation = result.evaluation();
        if (evaluation != null) {
            // Not the same number as the brew's own quality above, which is this one rounded.
            sb.append("\nunrounded quality: ").append(trim(evaluation.getTrueQuality()));
            List<String> deductions = new ArrayList<>();
            for (QualityDeduction deduction : evaluation.getDeductions()) {
                deductions.add(deduction.getDefect() + " (-" + trim(deduction.getQualityDeduction()) + ')');
            }
            if (deductions.isEmpty()) {
                sb.append(", nothing wrong with it");
            } else {
                sb.append("\n  lost to: ").append(String.join(", ", deductions));
                sb.append("\n  worst:   ").append(String.join(" / ", worstDefectNames(evaluation)));
            }
        }
        return sb.toString();
    }

    private static void checkName(List<String> mismatches, String key, @Nullable Object expected, Set<String> actual) {
        if (expected == null) {
            if (!actual.isEmpty()) {
                mismatches.add(key + ": expected none, got " + first(actual));
            }
            return;
        }
        String wanted = String.valueOf(expected);
        if (actual.isEmpty()) {
            mismatches.add(key + ": expected " + wanted + ", got none");
        } else if (actual.stream().noneMatch(a -> a.equalsIgnoreCase(wanted))) {
            mismatches.add(key + ": expected " + wanted + ", got " + String.join(" / ", actual));
        }
    }

    /**
     * The inverse of {@link #checkName}: passes unless the named recipe is what matched.
     * <p>
     * This is what the boundary rules assert. Past its tolerance a recipe is rejected outright, but
     * whether anything else steps in to claim those ingredients depends on the rest of the config,
     * so "not this one" is the strongest thing that can be derived from the recipe alone.
     */
    private static void checkNotName(List<String> mismatches, String key, Object expected, Set<String> actual) {
        String unwanted = String.valueOf(expected);
        if (actual.stream().anyMatch(a -> a.equalsIgnoreCase(unwanted))) {
            mismatches.add(key + ": expected anything but " + unwanted + ", but that is what matched");
        }
    }

    private static void checkNumber(List<String> mismatches, String key, Object expected, double actual) {
        Range range = asRange(expected);
        if (range == null) {
            mismatches.add(key + ": expectation " + expected + " is not a number or range");
        } else if (!range.matches(actual)) {
            mismatches.add(key + ": expected " + range + ", got " + trim(actual));
        }
    }

    private static void checkEquals(List<String> mismatches, String key, Object expected, Object actual) {
        boolean equal = expected instanceof String text && actual instanceof String actualText
            ? text.equalsIgnoreCase(actualText)
            : String.valueOf(expected).equalsIgnoreCase(String.valueOf(actual));
        if (!equal) {
            mismatches.add(key + ": expected " + expected + ", got " + actual);
        }
    }

    private static void checkSet(List<String> mismatches, String key, Object expected, Set<String> actual) {
        Set<String> wanted = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Object element : (List<?>) expected) {
            wanted.add(String.valueOf(element));
        }
        Set<String> got = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        got.addAll(actual);

        if (!wanted.equals(got)) {
            List<String> missing = wanted.stream().filter(w -> !got.contains(w)).toList();
            List<String> extra = got.stream().filter(g -> !wanted.contains(g)).toList();
            StringBuilder sb = new StringBuilder(key + ": expected " + wanted + ", got " + got);
            if (!missing.isEmpty()) {
                sb.append("\n    missing: ").append(String.join(", ", missing));
            }
            if (!extra.isEmpty()) {
                sb.append("\n    unexpected: ").append(String.join(", ", extra));
            }
            mismatches.add(sb.toString());
        }
    }

    private static void checkAnyOf(List<String> mismatches, String key, Object expected, Set<String> actual) {
        String wanted = String.valueOf(expected);
        if (actual.stream().noneMatch(a -> a.equalsIgnoreCase(wanted))) {
            mismatches.add(key + ": expected " + wanted + ", got "
                + (actual.isEmpty() ? "no defects at all" : String.join(" / ", actual)));
        }
    }

    private static Set<String> recipeNames(@Nullable BRecipe recipe) {
        if (recipe == null) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        if (recipe.getId() != null && !recipe.getId().isBlank()) {
            names.add(recipe.getId());
        }
        names.add(stripColor(recipe.getRecipeName()));
        return names;
    }

    private static Set<String> cauldronNames(@Nullable BCauldronRecipe recipe) {
        if (recipe == null) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        if (recipe.getId() != null && !recipe.getId().isBlank()) {
            names.add(recipe.getId());
        }
        names.add(stripColor(recipe.getName()));
        return names;
    }

    private static Set<String> effectNames(Brew brew) {
        Set<String> names = new LinkedHashSet<>();
        List<BEffect> effects = brew.getEffects(); // null when no recipe matched
        if (effects != null) {
            for (BEffect effect : effects) {
                names.add(effect.getType().getKey().getKey());
            }
        }
        return names;
    }

    private static Set<String> defectNames(@Nullable RecipeEvaluation evaluation) {
        Set<String> names = new LinkedHashSet<>();
        if (evaluation != null) {
            for (QualityDeduction deduction : evaluation.getDeductions()) {
                names.add(deduction.getDefect().toString());
            }
        }
        return names;
    }

    private static Set<String> worstDefectNames(@Nullable RecipeEvaluation evaluation) {
        Set<String> names = new LinkedHashSet<>();
        if (evaluation != null) {
            for (BrewDefect defect : evaluation.getWorstDefects()) {
                names.add(defect.toString());
            }
        }
        return names;
    }

    private static @Nullable Range asRange(@Nullable Object value) {
        if (value instanceof Number number) {
            return new Range(number.doubleValue(), number.doubleValue());
        }
        if (value instanceof String text) {
            Matcher matcher = RANGE.matcher(text);
            if (matcher.matches()) {
                return new Range(Double.parseDouble(matcher.group(1)), Double.parseDouble(matcher.group(2)));
            }
        }
        return null;
    }

    private static Set<String> woodNames() {
        Set<String> names = new LinkedHashSet<>();
        for (BarrelWoodType wood : BarrelWoodType.values()) {
            names.add(wood.name());
        }
        return names;
    }

    private static Set<String> outcomeNames() {
        return new LinkedHashSet<>(Arrays.stream(Outcome.values()).map(Enum::name).toList());
    }

    private static String stripColor(@Nullable String text) {
        String stripped = text == null ? null : ChatColor.stripColor(text);
        return stripped == null ? "" : stripped;
    }

    private static String first(Set<String> values) {
        return values.iterator().next();
    }

    private static String trim(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.3f", value);
    }

}
