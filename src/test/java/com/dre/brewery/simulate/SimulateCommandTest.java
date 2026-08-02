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
import com.dre.brewery.BreweryPlugin;
import com.dre.brewery.commands.subcommands.SimulateCommand;
import com.dre.brewery.configuration.ConfigManager;
import com.dre.brewery.configuration.files.Lang;
import com.dre.brewery.recipe.BRecipe;
import com.dre.brewery.recipe.RecipeItem;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks that {@code /brew simulate} still brews what {@link BrewSimulator} does, now that the
 * two share an engine. This is the parity check that would otherwise mean starting a server and
 * reading the console by hand.
 */
class SimulateCommandTest {

    @BeforeAll
    static void boot() {
        HeadlessBrewery.start(null);
    }

    @AfterAll
    static void shutdown() {
        HeadlessBrewery.shutdown();
    }

    @Test
    void commandBrewsWhatTheSimulatorBrews() {
        String reported = runCommand("simulate", "-c", "10", "-d", "2", "-a", "SPRUCE", "18", "WHEAT/10");

        SimulationResult direct = BrewSimulator.simulate(resolve(new SimulationRequest()
            .cookedTime(10)
            .distillRuns(2)
            .addAgeStep(BarrelWoodType.SPRUCE, 18)
            .addIngredient(ingredient("WHEAT/10"))));

        assertNotNull(direct.brew());
        assertEquals(10, direct.brew().getQuality(), "whiskey brewed to its recipe should be perfect");
        assertEquals(26, direct.brew().getAlc());

        assertTrue(reported.contains("quality=" + direct.brew().getQuality()),
            "console said: " + reported);
        assertTrue(reported.contains("alc=" + direct.brew().getAlc()),
            "console said: " + reported);
        assertTrue(reported.contains("wood=SPRUCE"), "console said: " + reported);
        assertTrue(reported.contains("distillRuns=2"), "console said: " + reported);
    }

    @Test
    void recipeFlagStillFillsInEverythingElse() {
        String reported = runCommand("simulate", "-r", "wheatbeer");

        assertTrue(reported.contains("quality=10"), "console said: " + reported);
        assertTrue(reported.contains("ageTime=2.0"), "console said: " + reported);
        assertTrue(reported.contains("wood=BIRCH"), "console said: " + reported);
    }

    @Test
    void missingCookTimeStillReportsTheUsualError() {
        String reported = runCommand("simulate", "-d", "2", "WHEAT/3");
        String expected = strip(ConfigManager.getConfig(Lang.class).getEntry("CMD_Missing_Cook_Time"));

        assertTrue(reported.contains(expected), "expected \"" + expected + "\", console said: " + reported);
    }

    @Test
    void ruinedByDistillingStillReportsTheUsualError() {
        // Wheatbeer is not a spirit; distilling it destroys it.
        String reported = runCommand("simulate", "-c", "8", "-d", "3", "WHEAT/3");
        String expected = strip(ConfigManager.getConfig(Lang.class).getEntry("CMD_Distill_Ruined"));

        assertTrue(reported.contains(expected), "expected \"" + expected + "\", console said: " + reported);
    }

    /**
     * Runs the subcommand from the console and returns everything it said, colour stripped.
     */
    private static String runCommand(String... args) {
        ConsoleCommandSenderMock console = (ConsoleCommandSenderMock) Bukkit.getConsoleSender();
        while (console.nextMessage() != null) {
            // drain anything left over from an earlier test
        }

        new SimulateCommand().execute(
            BreweryPlugin.getInstance(), ConfigManager.getConfig(Lang.class), console, "brewery", args);

        StringBuilder said = new StringBuilder();
        String message;
        while ((message = console.nextMessage()) != null) {
            said.append(strip(message)).append('\n');
        }
        return said.toString();
    }

    /**
     * Resolves a request, failing the test with the reason rather than handing the simulator null.
     */
    private static SimulationParameters resolve(SimulationRequest request) {
        SimulationRequest.Resolution resolution = request.resolve();
        if (resolution instanceof SimulationRequest.Resolution.Resolved resolved) {
            return resolved.parameters();
        }
        return fail("the request should have resolved, got " + resolution);
    }

    private static RecipeItem ingredient(String config) {
        BRecipe.IngredientResult result = BRecipe.loadIngredientVerbose(config);
        return ((BRecipe.IngredientResult.Success) result).ingredient();
    }

    private static String strip(String text) {
        String stripped = ChatColor.stripColor(text);
        return stripped == null ? "" : stripped;
    }

}
