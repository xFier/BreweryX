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

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a {@code checks:} selection into ordinary test cases.
 * <p>
 * Every generated case goes through the same {@link BrewSpec#resolve} and {@link BrewExpectation}
 * path as a hand-written one, so there is a single execution path and a single set of failure
 * messages. {@link #write} spells the same cases out as YAML, which is how a maintainer freezes
 * them: live checks re-derive their numbers when {@code recipes.yml} changes, and are therefore
 * blind to the engine's arithmetic changing underneath them, while frozen cases hold their numbers
 * still and catch exactly that.
 */
final class RuleExpander {

    private RuleExpander() {
    }

    /**
     * One rule applied to one subject.
     */
    record Expansion(RuleSubject subject, BrewRule rule, BrewSpec.Case testCase, String inputs) {
    }

    /**
     * Runs every selected rule against its subject.
     *
     * @param checks the selection
     * @param report collects the rules that skipped themselves, and is primed with the ones that
     *               did not so the report keeps rule order
     * @return the cases to run, in subject then rule order
     */
    static List<Expansion> expand(CheckSpec checks, CheckReport report) {
        List<Expansion> expansions = new ArrayList<>();
        for (CheckSpec.Selection selection : checks.resolve()) {
            RuleSubject subject = selection.subject();
            for (BrewRule rule : selection.rules()) {
                switch (rule.apply(subject)) {
                    case BrewRule.Result.Skipped skipped ->
                        report.skipped(subject, rule.name(), skipped.reason());
                    case BrewRule.Result.Applied applied -> {
                        BrewSpec.Case testCase = override(applied.testCase(),
                            selection.expect().get(rule.name()));
                        report.planned(subject, rule.name(), applied.inputs());
                        expansions.add(new Expansion(subject, rule, testCase, applied.inputs()));
                    }
                }
            }
        }
        return List.copyOf(expansions);
    }

    /**
     * Merges a maintainer's per-rule {@code expect:} over what the rule derived, so a hand-written
     * key wins and everything else is kept.
     */
    private static BrewSpec.Case override(BrewSpec.Case testCase, @Nullable Map<String, Object> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return testCase;
        }
        // Insertion order matters: it is the order the keys come out in the frozen YAML, and an
        // overridden case should still read like the ones around it.
        Map<String, Object> expect = new LinkedHashMap<>(testCase.expect());
        expect.putAll(overrides);
        return new BrewSpec.Case(testCase.name(), testCase.recipe(), testCase.ingredients(),
            testCase.cook(), testCase.distill(), testCase.age(),
            Collections.unmodifiableMap(expect));
    }

    // ---------------------------------------------------------------- freezing

    /**
     * Writes the expanded cases out as a spec file of plain {@code tests:}.
     *
     * @param file        where to write
     * @param expansions  what to write
     * @param configSource where the recipes came from, for the header
     */
    static void write(Path file, List<Expansion> expansions, String configSource) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            # =====================================================================================
            #  Generated brew tests
            #
            #  Written by:   gradlew brewTest -Pexpand
            #
            #  These are the `checks:` from your spec, spelled out with the numbers they worked out
            #  to. Commit this file and they become a fixed baseline: unlike the `checks:` they came
            #  from, they do not re-derive themselves, so they notice if the brewing engine's own
            #  arithmetic changes underneath them.
            #
            #  Edit freely -- but a re-run of `-Pexpand` overwrites the whole file, so move anything
            #  you want to keep into your own spec first.
            # =====================================================================================
            """);
        sb.append("#\n#  Recipes from: ").append(configSource).append('\n');
        sb.append("#  Cases: ").append(expansions.size()).append("\n\ntests:\n");

        String subject = null;
        for (Expansion expansion : expansions) {
            if (!expansion.subject().id().equals(subject)) {
                subject = expansion.subject().id();
                sb.append("\n  # ").append(subject).append("   ")
                    .append(expansion.subject().headline()).append('\n');
            }
            appendCase(sb, expansion);
        }

        try {
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file.toAbsolutePath(), e);
        }
    }

    private static void appendCase(StringBuilder sb, Expansion expansion) {
        BrewSpec.Case testCase = expansion.testCase();
        sb.append("  # ").append(expansion.rule().summary()).append('\n');
        sb.append("  - name: ").append(quote(testCase.name())).append('\n');
        if (testCase.recipe() != null) {
            sb.append("    recipe: ").append(quote(testCase.recipe())).append('\n');
        }
        if (!testCase.ingredients().isEmpty()) {
            sb.append("    ingredients: [").append(String.join(", ", testCase.ingredients())).append("]\n");
        }
        if (testCase.cook() != null) {
            sb.append("    cook: ").append(testCase.cook()).append('\n');
        }
        if (testCase.distill() != null) {
            sb.append("    distill: ").append(testCase.distill()).append('\n');
        }
        if (testCase.age() != null) {
            if (testCase.age().isEmpty()) {
                sb.append("    age: []\n");
            } else {
                sb.append("    age:\n");
                for (BrewSpec.AgeStep step : testCase.age()) {
                    sb.append("      - { wood: ").append(step.wood())
                        .append(", years: ").append(number(step.years())).append(" }\n");
                }
            }
        }
        sb.append("    expect:\n");
        for (Map.Entry<String, Object> entry : testCase.expect().entrySet()) {
            sb.append("      ").append(entry.getKey()).append(": ").append(value(entry.getValue())).append('\n');
        }
        sb.append('\n');
    }

    private static String value(@Nullable Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return "[]";
            }
            List<String> written = new ArrayList<>();
            for (Object element : list) {
                written.add(quote(String.valueOf(element)));
            }
            return "[" + String.join(", ", written) + "]";
        }
        if (value instanceof Float || value instanceof Double) {
            return number(((Number) value).floatValue());
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return quote(String.valueOf(value));
    }

    private static String number(float value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.4f", value);
    }

    /**
     * Quotes anything YAML could read as something other than a plain string. Defect strings carry
     * braces, and recipe names can carry anything at all.
     */
    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

}
