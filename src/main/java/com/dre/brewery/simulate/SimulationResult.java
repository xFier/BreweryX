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
import com.dre.brewery.recipe.RecipeEvaluation;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * What a simulated brewing run produced.
 *
 * @param item           the potion item, or null if the run never got that far
 * @param brew           the brew carried by that item, or null if the run never got that far
 * @param cauldronRecipe the ferment that came out of the cauldron, if any. Set whenever the cook
 *                       stage did not by itself produce a finished brew — which includes brews
 *                       that only become their recipe once distilled or aged — mirroring when
 *                       {@link com.dre.brewery.BIngredients#cook} falls back to one
 * @param evaluation     the quality breakdown of the last stage that looked for a recipe, or null
 *                       if no recipes are registered at all
 * @param outcome        how the run ended
 */
public record SimulationResult(
    @Nullable ItemStack item,
    @Nullable Brew brew,
    @Nullable BCauldronRecipe cauldronRecipe,
    @Nullable RecipeEvaluation evaluation,
    Outcome outcome
) {
}
