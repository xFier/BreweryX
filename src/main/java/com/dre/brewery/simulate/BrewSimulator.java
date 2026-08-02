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
import com.dre.brewery.Brew;
import com.dre.brewery.recipe.BCauldronRecipe;
import com.dre.brewery.recipe.BestRecipeResult;
import com.dre.brewery.recipe.RecipeEvaluation;
import com.dre.brewery.recipe.RecipeItem;
import com.dre.brewery.utility.Logging;
import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.jetbrains.annotations.Nullable;

/**
 * Runs ingredients through the whole brewing pipeline — cauldron, still and barrel — without a
 * cauldron, a still or a barrel.
 * <p>
 * This is what backs {@code /brew simulate}, and it is deliberately free of any messaging or
 * inventory handling so that it can also be driven from tests. Callers map {@link Outcome} onto
 * whatever they need to say to the user.
 */
public final class BrewSimulator {

    private BrewSimulator() {
    }

    /**
     * Brews the given parameters.
     *
     * @param simulation what to brew and how
     * @return the resulting item and brew, plus enough context to explain them
     */
    public static SimulationResult simulate(SimulationParameters simulation) {
        BIngredients ingredients = new BIngredients();
        for (RecipeItem item : simulation.ingredients()) {
            for (int i = 0; i < item.getAmount(); i++) {
                ingredients.addGeneric(item);
            }
        }
        Logging.debugLog(String.format("simulate: ingredients=%s", ingredients));

        ItemStack item = ingredients.cook(simulation.cookedTime(), simulation.brewer());
        if (item == null) {
            // BIngredients.cook() returns null when a plugin cancels the BrewModifyEvent
            Logging.debugLog("simulate: cooking was cancelled by another plugin");
            return new SimulationResult(null, null, null, null, Outcome.CANCELLED);
        }

        Brew brew = new Brew(ingredients);
        BCauldronRecipe cauldronRecipe = findCauldronRecipe(ingredients, simulation.cookedTime());
        Logging.debugLog(String.format("simulate: cooked for %d minutes: %s",
            simulation.cookedTime(), ChatColor.stripColor(brew.toString())));

        if (simulation.distillRuns().isPresent()) {
            if (!(item.getItemMeta() instanceof PotionMeta meta)) {
                return new SimulationResult(item, brew, cauldronRecipe,
                    evaluate(ingredients, brew), Outcome.CANNOT_DISTILL);
            }

            int distillRuns = simulation.distillRuns().getAsInt();
            for (int i = 0; i < distillRuns; i++) {
                brew.distillSlot(item, meta);
            }
            Logging.debugLog(String.format("simulate: distilled for %d runs: %s",
                distillRuns, ChatColor.stripColor(brew.toString())));

            if (!brew.hasRecipe()) {
                return new SimulationResult(item, brew, cauldronRecipe,
                    evaluate(ingredients, brew), Outcome.DISTILL_RUINED);
            }
        }

        for (Age age : simulation.ageSteps()) {
            BarrelWoodType barrelType = age.barrelType();
            if (barrelType == null) {
                return new SimulationResult(item, brew, cauldronRecipe,
                    evaluate(ingredients, brew), Outcome.MISSING_BARREL_TYPE);
            }
            brew.age(item, age.ageTime(), barrelType);
            Logging.debugLog(String.format("simulate: aged for %.3f years in %s barrel: %s",
                age.ageTime(), barrelType.getFormattedName(), ChatColor.stripColor(brew.toString())));
        }

        Outcome outcome = !simulation.ageSteps().isEmpty() && !brew.hasRecipe()
            ? Outcome.AGE_RUINED
            : Outcome.OK;

        return new SimulationResult(item, brew, cauldronRecipe, evaluate(ingredients, brew), outcome);
    }

    /**
     * Mirrors the fallback in {@link BIngredients#cook}: a cauldron recipe only applies when no
     * {@code BRecipe} matched the cook and the cauldron was actually heated.
     */
    private static @Nullable BCauldronRecipe findCauldronRecipe(BIngredients ingredients, int cookedTime) {
        if (cookedTime <= 0 || ingredients.getCookRecipe() != null) {
            return null;
        }
        return ingredients.getCauldronRecipe();
    }

    /**
     * Re-runs the recipe search for whichever stage last determined the brew's recipe, to recover
     * the quality breakdown behind it. These lookups are pure, so this only costs time.
     */
    private static @Nullable RecipeEvaluation evaluate(BIngredients ingredients, Brew brew) {
        BestRecipeResult result;
        // Half a day is the same threshold Brew.age() uses before it re-searches; below it the
        // brew keeps whatever the cook or the still gave it, and its wood has not shifted either.
        if (brew.getAgeTime() > 0.5) {
            result = ingredients.getAgeRecipeFull(brew.getWood(), brew.getAgeTime(), brew.getDistillRuns() > 0);
        } else if (brew.getDistillRuns() > 0) {
            result = ingredients.getDistillRecipeFull(brew.getWood(), brew.getAgeTime());
        } else {
            result = ingredients.getCookRecipeFull();
        }

        return switch (result) {
            case BestRecipeResult.Found found -> found.eval();
            case BestRecipeResult.Error error -> error.eval();
            case BestRecipeResult.NoRecipesRegistered ignored -> null;
        };
    }

}
