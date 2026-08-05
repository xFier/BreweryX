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

import com.dre.brewery.BIngredients;
import com.dre.brewery.BarrelWoodType;
import com.dre.brewery.recipe.BRecipe;
import com.dre.brewery.recipe.RecipeItem;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link RecipeMath} to the engine it is meant to predict.
 * <p>
 * The rule battery derives its inputs from {@code RecipeMath}, which re-implements the tolerance
 * formulas rather than calling {@link BRecipe#allowedTimeDiff} and {@link BRecipe#allowedCountDiff}.
 * That independence is the whole point — a test that asks the engine what it expects cannot catch
 * the engine changing — but it does mean the two can drift apart unnoticed. This turns that drift
 * into one obvious failure here, rather than into quietly relocated boundaries in every generated
 * case.
 * <p>
 * Runs in the ordinary {@code test} task, since it needs no spec file.
 */
class ToleranceParityTest {

    @BeforeAll
    static void boot() {
        HeadlessBrewery.start(null);
    }

    @AfterAll
    static void shutdown() {
        HeadlessBrewery.shutdown();
    }

    @Test
    void cookToleranceMatchesTheEngine() {
        List<String> drift = new ArrayList<>();
        for (BRecipe recipe : BRecipe.getAllRecipes()) {
            int engine = recipe.allowedTimeDiff(recipe.getCookingTime());
            int oracle = RecipeMath.cookTolerance(recipe);
            if (engine != oracle) {
                drift.add(describe(recipe) + " cook time " + recipe.getCookingTime()
                    + ": engine allows ±" + engine + ", RecipeMath predicts ±" + oracle);
            }
        }
        assertNoDrift("cook", drift);
    }

    @Test
    void countToleranceMatchesTheEngine() {
        List<String> drift = new ArrayList<>();
        for (BRecipe recipe : BRecipe.getAllRecipes()) {
            for (RecipeItem ingredient : recipe.getIngredients()) {
                int amount = ingredient.getAmount();
                int engine = recipe.allowedCountDiff(amount);
                int oracle = RecipeMath.countTolerance(recipe, amount);
                if (engine != oracle) {
                    drift.add(describe(recipe) + " " + ingredient.getDebugID() + " ×" + amount
                        + ": engine allows ±" + engine + ", RecipeMath predicts ±" + oracle);
                }
            }
        }
        assertNoDrift("ingredient count", drift);
    }

    /**
     * The formula floors its input at 8, so a one-item recipe and an eight-item one get the same
     * tolerance. Recipes are not guaranteed to cover both sides of that floor, so sweep it here.
     */
    @Test
    void toleranceMatchesTheEngineAcrossTheWholeRange() {
        BRecipe probe = BRecipe.getAllRecipes().stream().findFirst().orElse(null);
        assertTrue(probe != null, "no recipes loaded, so nothing could be compared");

        List<String> drift = new ArrayList<>();
        // Recipes are global singletons, so put the difficulty back however this ends.
        int originalDifficulty = probe.getDifficulty();
        try {
            for (int difficulty = 0; difficulty <= 10; difficulty++) {
                probe.setDifficulty(difficulty);
                for (int value : new int[]{1, 2, 5, 7, 8, 9, 10, 15, 20, 40, 60, 100, 240}) {
                    int engine = probe.allowedTimeDiff(value);
                    int oracle = RecipeMath.countTolerance(probe, value);
                    if (engine != oracle) {
                        drift.add("difficulty " + difficulty + ", value " + value
                            + ": engine allows ±" + engine + ", RecipeMath predicts ±" + oracle);
                    }
                }
            }
        } finally {
            probe.setDifficulty(originalDifficulty);
        }
        assertNoDrift("swept", drift);
    }

    /**
     * The wood deduction is the one factor with two implementations behind it, so pin the one the
     * rules actually run against. The {@code newBarrelTypeAlgorithm} branch is deliberately not
     * compared: {@code getWoodQualityNew} returns a quality where its caller wants a deduction, so
     * the engine's scale is inverted there and {@link RecipeMath} follows the table documented
     * beside it instead. The wood rules failing under that setting is the intended signal.
     */
    @Test
    void woodDeductionMatchesTheEngine() {
        if (RecipeMath.newBarrelTypeAlgorithm()) {
            return;
        }
        List<String> drift = new ArrayList<>();
        for (BRecipe recipe : BRecipe.getAllRecipes()) {
            if (recipe.usesAnyWood()) {
                continue;
            }
            for (BarrelWoodType wood : BarrelWoodType.values()) {
                if (!wood.isSpecific()) {
                    continue;
                }
                // Wood quality does not depend on what is in the pot, so an empty one will do.
                int engine = new BIngredients().getWoodQuality(recipe, wood);
                int oracle = RecipeMath.factorQuality(RecipeMath.woodDeduction(recipe, wood));
                if (engine != oracle) {
                    drift.add(describe(recipe) + " aged in " + wood.name()
                        + ": engine gives quality " + engine + ", RecipeMath predicts " + oracle);
                }
            }
        }
        assertNoDrift("wood", drift);
    }

    private static void assertNoDrift(String what, List<String> drift) {
        assertTrue(drift.isEmpty(), () -> "RecipeMath no longer predicts the engine's " + what
            + " tolerance. Either the engine's formula changed and RecipeMath must follow, or "
            + "RecipeMath is wrong:\n  - " + String.join("\n  - ", drift));
    }

    private static String describe(BRecipe recipe) {
        return recipe.getId() + " (difficulty " + recipe.getDifficulty() + ")";
    }

}
