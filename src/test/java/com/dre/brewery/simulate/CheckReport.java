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

import com.dre.brewery.Brew;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The standard rundown of what the rules made of each recipe and ferment.
 * <p>
 * One block per subject: its own numbers, the tolerances the rules were derived from, a line per
 * rule, and a tally. Rules that skipped themselves say why, which is as much a part of the answer
 * as the ones that ran — "wood rules skipped, recipe accepts any barrel" is how a maintainer sees
 * that a recipe is looser than they thought.
 * <p>
 * Deliberately plain ASCII: this goes through Gradle's console on whatever encoding the machine
 * happens to have.
 */
final class CheckReport {

    private static final int RULE_WIDTH = 20;
    private static final int INPUT_WIDTH = 44;

    private final Map<String, Block> blocks = new LinkedHashMap<>();

    private enum Status {
        PASSED("  ok  "),
        FAILED(" FAIL "),
        SKIPPED(" skip "),
        NOT_RUN("  --  ");

        private final String marker;

        Status(String marker) {
            this.marker = marker;
        }
    }

    private static final class Line {
        private final String rule;
        private String inputs;
        private String outcome = "";
        private Status status = Status.NOT_RUN;

        private Line(String rule, String inputs) {
            this.rule = rule;
            this.inputs = inputs;
        }
    }

    private static final class Block {
        private final RuleSubject subject;
        private final Map<String, Line> lines = new LinkedHashMap<>();

        private Block(RuleSubject subject) {
            this.subject = subject;
        }
    }

    /** Notes a rule that will run, so the report keeps rule order even if it never reports back. */
    void planned(RuleSubject subject, String rule, String inputs) {
        line(subject, rule).inputs = inputs;
    }

    /** Notes a rule that had nothing to say about this subject. */
    void skipped(RuleSubject subject, String rule, String reason) {
        Line line = line(subject, rule);
        line.status = Status.SKIPPED;
        line.inputs = "";
        line.outcome = reason;
    }

    void passed(RuleSubject subject, String rule, SimulationResult result) {
        Line line = line(subject, rule);
        line.status = Status.PASSED;
        line.outcome = summarise(result);
    }

    void failed(RuleSubject subject, String rule, String detail) {
        Line line = line(subject, rule);
        line.status = Status.FAILED;
        line.outcome = detail;
    }

    boolean isEmpty() {
        return blocks.isEmpty();
    }

    private Line line(RuleSubject subject, String rule) {
        Block block = blocks.computeIfAbsent(subject.id(), id -> new Block(subject));
        return block.lines.computeIfAbsent(rule, name -> new Line(name, ""));
    }

    /**
     * @return the whole report, ready to print
     */
    String format() {
        StringBuilder sb = new StringBuilder();
        int passed = 0;
        int failed = 0;
        int skipped = 0;

        for (Block block : blocks.values()) {
            sb.append('\n').append(block.subject.id()).append("   ").append(block.subject.headline()).append('\n');
            if (block.subject instanceof RuleSubject.Recipe recipe) {
                sb.append("  tolerances   ").append(recipe.tolerances()).append('\n');
            }

            int blockPassed = 0;
            int blockFailed = 0;
            int blockSkipped = 0;
            for (Line line : block.lines.values()) {
                sb.append(' ').append(line.status.marker).append(' ')
                    .append(pad(line.rule, RULE_WIDTH))
                    .append(pad(line.inputs, INPUT_WIDTH))
                    .append(line.outcome.isEmpty() ? "" : line.outcome)
                    .append('\n');
                switch (line.status) {
                    case PASSED -> blockPassed++;
                    case FAILED -> blockFailed++;
                    case SKIPPED -> blockSkipped++;
                    case NOT_RUN -> { /* counted nowhere; it never got as far as an answer */ }
                }
            }

            sb.append("       ").append(tally(blockPassed, blockFailed, blockSkipped)).append('\n');
            passed += blockPassed;
            failed += blockFailed;
            skipped += blockSkipped;
        }

        sb.append("\n  ").append(blocks.size()).append(" checked: ")
            .append(tally(passed, failed, skipped)).append('\n');
        return sb.toString();
    }

    private static String tally(int passed, int failed, int skipped) {
        return passed + " passed   " + failed + " failed   " + skipped + " skipped";
    }

    /**
     * What actually came out, in the few words the report has room for.
     */
    private static String summarise(SimulationResult result) {
        Brew brew = result.brew();
        if (brew != null && brew.hasRecipe()) {
            // Name the recipe: a rule asserting `notRecipe` passes precisely when this is some
            // other recipe, and which one it was is the whole story.
            return pad(brew.getCurrentRecipe().getId(), 14)
                + "quality " + brew.getQuality() + "  alcohol " + brew.getAlc();
        }
        if (result.cauldronRecipe() != null) {
            return "ferment " + result.cauldronRecipe().getId();
        }
        return "no match (" + result.outcome() + ")";
    }

    private static String pad(@Nullable String text, int width) {
        String value = text == null ? "" : text;
        if (value.length() >= width) {
            return value + "  ";
        }
        return value + " ".repeat(width - value.length());
    }

}
