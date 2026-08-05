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

import com.dre.brewery.recipe.BCauldronRecipe;
import com.dre.brewery.recipe.BRecipe;
import com.dre.brewery.recipe.RecipeItem;
import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Something a rule can be pointed at: a brewing recipe, or a cauldron ferment.
 * <p>
 * The two are far enough apart to need their own rules — a ferment has no cook time, no difficulty
 * and no quality, only a list of ingredients it matches more or less well — so most rules apply to
 * one kind and skip the other.
 */
sealed interface RuleSubject {

    /** How the maintainer names this subject in the spec, and how the report labels it. */
    String id();

    /** The subject's own numbers, as the first line of its report block. */
    String headline();

    /** A brewing recipe from {@code recipes.yml}. */
    record Recipe(BRecipe recipe) implements RuleSubject {

        @Override
        public String id() {
            return recipe.getId() != null && !recipe.getId().isBlank()
                ? recipe.getId()
                : ChatColor.stripColor(recipe.getRecipeName());
        }

        @Override
        public String headline() {
            List<String> parts = new ArrayList<>();
            parts.add("difficulty " + recipe.getDifficulty());
            parts.add("cook " + recipe.getCookingTime() + "m");
            if (recipe.needsDistilling()) {
                parts.add("distill " + recipe.getDistillruns());
            }
            if (recipe.needsToAge()) {
                parts.add("age " + recipe.getAge() + "y "
                    + (recipe.usesAnyWood() ? "any wood" : recipe.getWood().getFormattedName()));
            }
            parts.add("alcohol " + recipe.getAlcohol());
            return String.join("   ", parts);
        }

        /**
         * The tolerances the rules derive their inputs from, for the report's second line.
         */
        public String tolerances() {
            List<String> parts = new ArrayList<>();
            parts.add("cook ±" + RecipeMath.cookTolerance(recipe) + "m");
            for (RecipeItem ingredient : recipe.getIngredients()) {
                parts.add(ingredient.getDebugID() + " ±"
                    + RecipeMath.countTolerance(recipe, ingredient.getAmount()));
            }
            if (recipe.needsToAge()) {
                float age = RecipeMath.ageTolerance(recipe);
                parts.add("age " + (Float.isInfinite(age)
                    ? "unbounded"
                    : String.format(Locale.ROOT, "±%.2fy", age)));
            }
            return String.join("   ", parts);
        }
    }

    /** A cauldron ferment from {@code cauldron.yml}. */
    record Ferment(BCauldronRecipe recipe) implements RuleSubject {

        @Override
        public String id() {
            return recipe.getId() != null && !recipe.getId().isBlank()
                ? recipe.getId()
                : ChatColor.stripColor(recipe.getName());
        }

        @Override
        public String headline() {
            List<String> ingredients = new ArrayList<>();
            for (RecipeItem ingredient : recipe.getIngredients()) {
                ingredients.add(ingredient.getDebugID() + " ×" + ingredient.getAmount());
            }
            return "ferment   " + String.join(", ", ingredients);
        }
    }

}
