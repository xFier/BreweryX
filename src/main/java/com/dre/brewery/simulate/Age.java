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
import org.jetbrains.annotations.Nullable;

/**
 * A single stint in a barrel: which wood, and for how long.
 *
 * @param barrelType the wood of the barrel, or null if the caller could not determine one — the
 *                   simulation reports {@link Outcome#MISSING_BARREL_TYPE} rather than guessing
 * @param ageTime    the time spent in the barrel, in Minecraft days
 */
public record Age(@Nullable BarrelWoodType barrelType, float ageTime) {

    /**
     * The aging a recipe asks for, if it needs aging at all.
     * Recipes that accept any wood are aged in oak.
     *
     * @param recipe the recipe
     * @return the aging step, or null if the recipe does not need to age
     */
    public static @Nullable Age of(BRecipe recipe) {
        if (recipe.needsToAge()) {
            BarrelWoodType barrelType = recipe.getWood();
            return new Age(barrelType.isSpecific() ? barrelType : BarrelWoodType.OAK, recipe.getAge());
        }
        return null;
    }

}
