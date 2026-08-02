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

/**
 * How a simulated brewing run ended.
 */
public enum Outcome {

    /** The brew came out of the pipeline intact. */
    OK,
    /** Distilling was asked for, but what came out of the cauldron is not a potion. */
    CANNOT_DISTILL,
    /** Distilling left the brew without a recipe. */
    DISTILL_RUINED,
    /** Aging left the brew without a recipe. */
    AGE_RUINED,
    /** An aging step was asked for without a barrel wood. */
    MISSING_BARREL_TYPE,
    /** Another plugin cancelled the {@link com.dre.brewery.api.events.brew.BrewModifyEvent}. */
    CANCELLED

}
