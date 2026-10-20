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

/**
 * Something a rule can be pointed at: a brewing recipe, or a cauldron ferment.
 * <p>
 * The two are far enough apart to need their own rules — a ferment has no cook time, no difficulty
 * and no quality, only a list of ingredients it matches more or less well — so most rules apply to
 * one kind and skip the other.
 */
sealed interface RuleSubject {

    /** The id from the config file, which names the generated cases. */
    String id();

    /** The name players see, for the report. */
    String displayName();

    /** What the subject asks for, in a line of plain words. */
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
        public String displayName() {
            return orId(recipe.getRecipeName(), this);
        }

        @Override
        public String headline() {
            List<String> parts = new ArrayList<>();
            parts.add(ingredients(recipe.getIngredients(), true));
            parts.add("cook " + recipe.getCookingTime() + " min");
            if (recipe.needsDistilling()) {
                int runs = recipe.getDistillruns();
                parts.add("distill " + (runs == 1 ? "once" : runs == 2 ? "twice" : runs + " times"));
            }
            if (recipe.needsToAge()) {
                parts.add(BrewRules.years(recipe.getAge()) + " in "
                    + (recipe.usesAnyWood() ? "any barrel" : recipe.getWood().getFormattedName() + " barrel"));
            }
            parts.add("difficulty " + recipe.getDifficulty());
            return String.join(", ", parts);
        }
    }

    /** A cauldron brew from {@code cauldron.yml}. */
    record Ferment(BCauldronRecipe recipe) implements RuleSubject {

        @Override
        public String id() {
            return recipe.getId() != null && !recipe.getId().isBlank()
                ? recipe.getId()
                : ChatColor.stripColor(recipe.getName());
        }

        @Override
        public String displayName() {
            return orId(recipe.getName(), this);
        }

        @Override
        public String headline() {
            return "made from " + ingredients(recipe.getIngredients(), false);
        }
    }

    private static String ingredients(List<RecipeItem> items, boolean withAmounts) {
        List<String> parts = new ArrayList<>();
        for (RecipeItem item : items) {
            parts.add(withAmounts ? item.getAmount() + " " + BrewRules.item(item) : BrewRules.item(item));
        }
        return parts.isEmpty() ? "no ingredients" : String.join(" + ", parts);
    }

    private static String orId(String name, RuleSubject subject) {
        // recipes.yml writes colours as &6, which only become strippable once translated to §6.
        String stripped = name == null ? null
            : ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', name));
        return stripped == null || stripped.isBlank() ? subject.id() : stripped;
    }

}
