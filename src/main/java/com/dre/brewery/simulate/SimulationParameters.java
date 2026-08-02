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

import com.dre.brewery.recipe.RecipeItem;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.OptionalInt;

/**
 * A fully resolved brewing run, ready to hand to {@link BrewSimulator#simulate}.
 * Build one with {@link SimulationRequest}, which fills in whatever a base recipe implies.
 *
 * @param cookedTime  cooking time in minutes
 * @param distillRuns number of distill runs, or empty for no distilling
 * @param ageSteps    barrels the brew passes through in order; empty for no aging
 * @param ingredients what goes into the cauldron
 * @param brewer      the player credited as the brewer, only used for lore
 */
public record SimulationParameters(
    int cookedTime,
    OptionalInt distillRuns,
    List<Age> ageSteps,
    List<RecipeItem> ingredients,
    @Nullable Player brewer
) {
}
