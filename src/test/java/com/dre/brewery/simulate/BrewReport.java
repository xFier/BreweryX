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
import com.dre.brewery.recipe.BCauldronRecipe;
import com.dre.brewery.recipe.BRecipe;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Brews every recipe and cauldron brew a couple of dozen ways and describes what came out, for
 * whoever wrote {@code recipes.yml}.
 * <p>
 * Nothing here passes or fails. The rules in {@link BrewRules} still work out what each brew
 * <em>should</em> give, but a brew that turns into a different drink is a finding about the config
 * — "11 wheat brewed as Wheatbeer comes out as Beer" — not a broken test, so it is reported as an
 * overlap rather than suppressed. The only other thing flagged is a brew that matched its own
 * recipe but not the quality its numbers predict.
 * <p>
 * Deliberately plain ASCII: this goes through Gradle's console on whatever encoding the machine
 * happens to have.
 */
final class BrewReport {

    private static final int DESCRIPTION_WIDTH = 54;

    private final List<Block> drinks = new ArrayList<>();
    private final List<Block> cauldronBrews = new ArrayList<>();
    private final String configSource;

    private BrewReport(String configSource) {
        this.configSource = configSource;
    }

    /** One brew the report tried. */
    private record Line(String rule, String description, String outcome, @Nullable String becameOther,
                        List<String> unexpected) {

        /** Whether it came out as the rules predicted, overlap or no. */
        boolean asPredicted() {
            return becameOther == null && unexpected.isEmpty();
        }
    }

    /** Everything tried on one recipe or cauldron brew. */
    private record Block(RuleSubject subject, List<Line> lines, Set<String> notes) {

        /**
         * A cauldron brew whose own exact ingredients come out as something else can never be
         * made at all, which is worse than overlapping, so the health check names it separately.
         */
        boolean unreachable() {
            return lines.stream().anyMatch(line -> line.rule().equals("ferment-exact") && line.becameOther() != null);
        }

        boolean overlaps() {
            return lines.stream().anyMatch(line -> line.becameOther() != null);
        }

        boolean hasUnexpected() {
            return lines.stream().anyMatch(line -> !line.unexpected().isEmpty());
        }

        boolean healthy() {
            return !overlaps() && !hasUnexpected();
        }

        /** The lines where it came out as something else, grouped by what that was. */
        Map<String, List<Line>> overlapsByOther() {
            Map<String, List<Line>> byOther = new LinkedHashMap<>();
            for (Line line : lines) {
                if (line.becameOther() != null) {
                    byOther.computeIfAbsent(line.becameOther(), other -> new ArrayList<>()).add(line);
                }
            }
            return byOther;
        }
    }

    /**
     * Runs every rule against every recipe and cauldron brew. BreweryX must already be running
     * under {@link HeadlessBrewery}.
     */
    static BrewReport run() {
        BrewReport report = new BrewReport(HeadlessBrewery.configSource());
        for (BRecipe recipe : BRecipe.getAllRecipes()) {
            report.drinks.add(check(new RuleSubject.Recipe(recipe)));
        }
        for (BCauldronRecipe ferment : BCauldronRecipe.getAllRecipes()) {
            report.cauldronBrews.add(check(new RuleSubject.Ferment(ferment)));
        }
        return report;
    }

    private static Block check(RuleSubject subject) {
        Block block = new Block(subject, new ArrayList<>(), new LinkedHashSet<>());
        for (BrewRule rule : BrewRules.all()) {
            if (!rule.handles(subject)) {
                continue;
            }
            switch (rule.apply(subject)) {
                case BrewRule.Result.Skipped skipped -> {
                    if (skipped.reason() != null) {
                        block.notes().add(skipped.reason());
                    }
                }
                case BrewRule.Result.Applied applied ->
                    block.lines().add(brew(subject, rule.name(), applied.testCase(), applied.description()));
            }
        }
        return block;
    }

    private static Line brew(RuleSubject subject, String rule, BrewSpec.Case testCase, String description) {
        SimulationResult result;
        try {
            result = BrewSimulator.simulate(BrewSpec.resolve(testCase));
        } catch (RuntimeException e) {
            return new Line(rule, description, "could not be brewed", null,
                List.of(String.valueOf(e.getMessage())));
        }

        // Only a brew that should have stayed its own recipe can overlap. One pushed past the
        // recipe's limits is expected to stop being it, and landing on a simpler recipe instead
        // (whiskey mash that is never distilled is beer) is just what that looks like.
        boolean shouldStay = testCase.expect().containsKey("recipe")
            || testCase.expect().containsKey("cauldronRecipe");
        String other = shouldStay ? otherThing(subject, result) : null;
        // A brew that turned into something else is reported as that and nothing more: what the
        // rule expected of the original recipe no longer says anything about it.
        List<String> unexpected = other != null
            ? List.of()
            : BrewExpectation.check(result, testCase.expect());
        return new Line(rule, description, outcome(result), other, unexpected);
    }

    /**
     * The name of whatever claimed this brew in place of its subject, or null if nothing did. For a
     * recipe that is another recipe; for a cauldron brew it is any recipe, or another cauldron brew.
     */
    private static @Nullable String otherThing(RuleSubject subject, SimulationResult result) {
        Brew brew = result.brew();
        BRecipe recipe = brew != null && brew.hasRecipe() ? brew.getCurrentRecipe() : null;
        return switch (subject) {
            case RuleSubject.Recipe own -> recipe != null && recipe != own.recipe() ? name(recipe) : null;
            case RuleSubject.Ferment own -> {
                if (recipe != null) {
                    yield name(recipe);
                }
                BCauldronRecipe ferment = result.cauldronRecipe();
                yield ferment != null && ferment != own.recipe()
                    ? new RuleSubject.Ferment(ferment).displayName() + " (cauldron brew)"
                    : null;
            }
        };
    }

    /** What came out, in a few words. */
    private static String outcome(SimulationResult result) {
        Brew brew = result.brew();
        if (brew != null && brew.hasRecipe()) {
            return name(brew.getCurrentRecipe()) + ", quality " + brew.getQuality();
        }
        if (result.cauldronRecipe() != null) {
            return "no drink, just " + new RuleSubject.Ferment(result.cauldronRecipe()).displayName()
                + " from the cauldron";
        }
        return switch (result.outcome()) {
            case DISTILL_RUINED -> "no drink, ruined in the still";
            case AGE_RUINED -> "no drink, ruined in the barrel";
            default -> "no drink";
        };
    }

    private static String name(BRecipe recipe) {
        return new RuleSubject.Recipe(recipe).displayName();
    }

    // ---------------------------------------------------------------- formatting

    /** The findings alone: what a recipe author should read first. */
    String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("BreweryX recipe report\n");
        sb.append("Recipes from: ").append(configSource).append("\n\n");
        appendHealthCheck(sb);

        List<String> overlaps = new ArrayList<>();
        List<String> unexpected = new ArrayList<>();
        for (Block block : allBlocks()) {
            String name = block.subject().displayName();
            block.overlapsByOther().forEach((other, lines) -> {
                StringBuilder entry = new StringBuilder("  " + name + " can come out as " + other + ":\n");
                for (Line line : lines) {
                    entry.append("    - ").append(line.description()).append('\n');
                }
                overlaps.add(entry.toString());
            });
            for (Line line : block.lines()) {
                for (String detail : line.unexpected()) {
                    unexpected.add("  " + name + ", " + line.description() + ": " + detail + "\n");
                }
            }
        }

        if (!overlaps.isEmpty()) {
            sb.append("RECIPES THAT OVERLAP\n")
                .append("Brewed only a little off, these come out as a different drink. That can be on\n")
                .append("purpose (a weaker and a stronger beer); if not, make the recipes more distinct.\n\n");
            overlaps.forEach(sb::append);
            sb.append('\n');
        }
        if (!unexpected.isEmpty()) {
            sb.append("UNEXPECTED RESULTS\n")
                .append("These matched the right recipe but not the quality its numbers predict. Recipes\n")
                .append("with random amounts (WHEAT/2-4) can do this; otherwise it may be a BreweryX bug.\n\n");
            unexpected.forEach(sb::append);
        }
        return sb.toString();
    }

    /**
     * One line per kind of finding, counted in recipes rather than brews: a recipe author fixes
     * recipes, and one overlapping recipe can account for several brews.
     */
    private void appendHealthCheck(StringBuilder sb) {
        List<Block> unreachable = cauldronBrews.stream().filter(Block::unreachable).toList();
        List<Block> overlappingDrinks = drinks.stream().filter(Block::overlaps).toList();
        List<Block> overlappingBrews = cauldronBrews.stream()
            .filter(block -> block.overlaps() && !block.unreachable()).toList();
        List<Block> unexpected = allBlocks().stream().filter(Block::hasUnexpected).toList();
        long healthyDrinks = drinks.stream().filter(Block::healthy).count();
        long healthyBrews = cauldronBrews.stream().filter(Block::healthy).count();
        long needingALook = allBlocks().stream().filter(block -> !block.healthy()).count();

        sb.append("HEALTH CHECK   ").append(needingALook == 0
            ? "all good"
            : needingALook + (needingALook == 1 ? " recipe" : " recipes") + " worth a look").append("\n\n");

        sb.append(healthRow(true,
            healthyDrinks + " of " + drinks.size() + " drinks behave as written", List.of()));
        if (!overlappingDrinks.isEmpty()) {
            sb.append(healthRow(false, overlappingDrinks.size()
                + " turn into a different drink when brewed slightly off:", overlappingDrinks));
        }
        sb.append(healthRow(true,
            healthyBrews + " of " + cauldronBrews.size() + " cauldron brews behave as written", List.of()));
        if (!unreachable.isEmpty()) {
            sb.append(healthRow(false, unreachable.size()
                + " can never be made, because a drink always wins:", unreachable));
        }
        if (!overlappingBrews.isEmpty()) {
            sb.append(healthRow(false, overlappingBrews.size()
                + " turn into something else when brewed slightly off:", overlappingBrews));
        }
        if (!unexpected.isEmpty()) {
            sb.append(healthRow(false, unexpected.size()
                + " gave a quality their numbers do not predict:", unexpected));
        }

        long predicted = allBlocks().stream().flatMap(block -> block.lines().stream())
            .filter(Line::asPredicted).count();
        sb.append("\n          ").append(predicted).append(" of ").append(brewCount())
            .append(" test brews came out as predicted.\n\n");
    }

    private static String healthRow(boolean ok, String text, List<Block> named) {
        StringBuilder row = new StringBuilder(ok ? "  [ ok ]  " : "  [ !! ]  ").append(text).append('\n');
        if (named.isEmpty()) {
            return row.toString();
        }
        // Wrapped by hand: the console will not do it, and the names are what the reader is after.
        StringBuilder current = new StringBuilder("            ");
        for (int i = 0; i < named.size(); i++) {
            String name = named.get(i).subject().displayName() + (i < named.size() - 1 ? ", " : "");
            if (current.length() + name.length() > 88 && current.length() > 12) {
                row.append(current.toString().stripTrailing()).append('\n');
                current = new StringBuilder("            ");
            }
            current.append(name);
        }
        return row.append(current).append('\n').toString();
    }

    /** The summary followed by every brew tried, recipe by recipe. */
    String full() {
        StringBuilder sb = new StringBuilder(summary());
        appendSection(sb, "DRINKS", drinks);
        appendSection(sb, "CAULDRON BREWS", cauldronBrews);
        sb.append("\nLines marked ! came out as a different drink; see RECIPES THAT OVERLAP above.\n");
        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, String title, List<Block> blocks) {
        if (blocks.isEmpty()) {
            return;
        }
        sb.append("\n\n========== ").append(title).append(" ==========\n");
        for (Block block : blocks) {
            RuleSubject subject = block.subject();
            sb.append('\n').append(subject.displayName());
            if (!subject.id().equalsIgnoreCase(subject.displayName())) {
                sb.append("   (").append(subject.id()).append(')');
            }
            sb.append('\n');
            sb.append("  ").append(subject.headline()).append("\n\n");
            for (Line line : block.lines()) {
                sb.append(line.becameOther() != null ? "  ! " : "    ")
                    .append(pad(line.description()))
                    .append(line.outcome())
                    .append('\n');
                for (String detail : line.unexpected()) {
                    sb.append("      unexpected: ").append(detail).append('\n');
                }
            }
            for (String note : block.notes()) {
                sb.append("    Note: ").append(note).append('\n');
            }
            long predicted = block.lines().stream().filter(Line::asPredicted).count();
            sb.append("    ").append(block.healthy() ? "[ ok ]  " : "[ !! ]  ")
                .append(predicted).append(" of ").append(block.lines().size())
                .append(" brews came out as predicted\n");
        }
    }

    private List<Block> allBlocks() {
        List<Block> all = new ArrayList<>(drinks);
        all.addAll(cauldronBrews);
        return all;
    }

    private int brewCount() {
        return allBlocks().stream().mapToInt(block -> block.lines().size()).sum();
    }

    private static String pad(String text) {
        if (text.length() >= DESCRIPTION_WIDTH) {
            return text + "  ";
        }
        return text + " " + ".".repeat(DESCRIPTION_WIDTH - text.length() - 1) + " ";
    }

}
