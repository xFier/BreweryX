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
import com.dre.brewery.recipe.BRecipe;
import com.dre.brewery.recipe.RecipeItem;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Builds a {@link SimulationParameters}, filling in anything left unset from a base recipe.
 * <p>
 * This is where the defaulting rules live, so that {@code /brew simulate} and any other caller
 * cannot drift apart on what {@code --recipe} implies.
 */
public final class SimulationRequest {

    /**
     * A parameter that was neither given nor derivable from a base recipe.
     */
    public enum Field {
        COOK_TIME,
        INGREDIENTS
    }

    /**
     * The outcome of {@link #resolve()}.
     */
    public sealed interface Resolution {
        record Resolved(SimulationParameters parameters) implements Resolution {}
        record Missing(Field field) implements Resolution {}
    }

    private @Nullable BRecipe recipe = null;

    private int cookedTime = -1;
    private boolean cookedTimeSet = false;

    private int distillRuns = -1;
    private boolean distillRunsSet = false;

    private final List<Age> ageSteps = new ArrayList<>();
    private boolean ageSet = false;

    private final List<RecipeItem> ingredients = new ArrayList<>();
    private @Nullable Player brewer = null;

    /**
     * Sets the base recipe. Anything not set explicitly is taken from it.
     */
    public SimulationRequest recipe(@Nullable BRecipe recipe) {
        this.recipe = recipe;
        return this;
    }

    public SimulationRequest cookedTime(int minutes) {
        this.cookedTime = minutes;
        this.cookedTimeSet = true;
        return this;
    }

    /**
     * Sets how many times to distill. Zero means explicitly no distilling, overriding whatever the
     * base recipe would imply — running the still zero times is not the same as skipping it, since
     * the still can reject a brew it cannot take.
     */
    public SimulationRequest distillRuns(int runs) {
        this.distillRuns = runs;
        this.distillRunsSet = true;
        return this;
    }

    /**
     * Adds a barrel for the brew to pass through. Call more than once to move the brew between
     * barrels; each step is aged in turn. A null wood resolves to
     * {@link Outcome#MISSING_BARREL_TYPE} when the step is reached.
     */
    public SimulationRequest addAgeStep(@Nullable BarrelWoodType wood, float years) {
        this.ageSteps.add(new Age(wood, years));
        this.ageSet = true;
        return this;
    }

    /**
     * Marks the brew as explicitly un-aged, overriding whatever the base recipe would imply.
     */
    public SimulationRequest noAging() {
        this.ageSteps.clear();
        this.ageSet = true;
        return this;
    }

    public SimulationRequest addIngredient(RecipeItem item) {
        this.ingredients.add(item);
        return this;
    }

    public SimulationRequest brewer(@Nullable Player brewer) {
        this.brewer = brewer;
        return this;
    }

    /**
     * Applies the base recipe's defaults to everything left unset.
     *
     * @return the resolved parameters, or the field that could not be filled in
     */
    public Resolution resolve() {
        int cookedTime;
        if (cookedTimeSet) {
            cookedTime = this.cookedTime;
        } else if (recipe != null) {
            cookedTime = recipe.getCookingTime();
        } else {
            return new Resolution.Missing(Field.COOK_TIME);
        }

        OptionalInt distill;
        if (distillRunsSet) {
            distill = distillRuns > 0 ? OptionalInt.of(distillRuns) : OptionalInt.empty();
        } else if (recipe != null && recipe.needsDistilling()) {
            distill = OptionalInt.of(recipe.getDistillruns());
        } else {
            distill = OptionalInt.empty();
        }

        List<Age> ageSteps;
        if (ageSet) {
            ageSteps = List.copyOf(this.ageSteps);
        } else if (recipe != null) {
            Age fromRecipe = Age.of(recipe);
            ageSteps = fromRecipe == null ? List.of() : List.of(fromRecipe);
        } else {
            ageSteps = List.of();
        }

        List<RecipeItem> ingredients;
        if (!this.ingredients.isEmpty()) {
            ingredients = List.copyOf(this.ingredients);
        } else if (recipe != null && !recipe.getIngredients().isEmpty()) {
            ingredients = List.copyOf(recipe.getIngredients());
        } else {
            return new Resolution.Missing(Field.INGREDIENTS);
        }

        return new Resolution.Resolved(
            new SimulationParameters(cookedTime, distill, ageSteps, ingredients, brewer));
    }

}
