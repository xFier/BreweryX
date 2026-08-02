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

import com.dre.brewery.recipe.BRecipe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the headless bootstrap itself, independently of any {@code brew-tests.yml}.
 * <p>
 * Unlike {@link BrewSpecTest} this needs no spec file, so it runs as part of an ordinary
 * {@code gradle build} and will catch the fixture rotting — a MockBukkit upgrade, a change to
 * {@code BreweryPlugin.onEnable()} the fixture no longer mirrors, or the plugin's main class
 * becoming final again.
 */
class HeadlessBreweryTest {

    @BeforeAll
    static void boot() {
        HeadlessBrewery.start(null);
    }

    @AfterAll
    static void shutdown() {
        HeadlessBrewery.shutdown();
    }

    @Test
    void loadsTheBuiltInRecipes() {
        assertTrue(BRecipe.getAllRecipes().size() > 10,
            "the built-in recipes should have loaded, got " + BRecipe.getAllRecipes().size());
        assertNotNull(BRecipe.getMatching("wheatbeer"), "wheatbeer should be one of them");
    }

    @Test
    void brewsARecipeEndToEnd() {
        BRecipe wheatbeer = BRecipe.getMatching("wheatbeer");
        SimulationRequest.Resolution resolution = new SimulationRequest().recipe(wheatbeer).resolve();
        assertInstanceOfResolved(resolution);

        SimulationParameters parameters = ((SimulationRequest.Resolution.Resolved) resolution).parameters();
        SimulationResult result = BrewSimulator.simulate(parameters);

        assertEquals(Outcome.OK, result.outcome());
        assertNotNull(result.item(), "cooking should have produced a potion");
        assertNotNull(result.brew(), "the potion should carry a brew");
        assertNotNull(result.evaluation(), "the brew should come with a quality breakdown");

        assertEquals(10, result.brew().getQuality());
        assertEquals(wheatbeer, result.brew().getCurrentRecipe());
        assertTrue(result.evaluation().getDeductions().isEmpty(),
            "nothing should have cost it quality, got " + result.evaluation());
    }

    private static void assertInstanceOfResolved(SimulationRequest.Resolution resolution) {
        assertTrue(resolution instanceof SimulationRequest.Resolution.Resolved,
            "a recipe on its own should be enough to resolve, got " + resolution);
    }

}
