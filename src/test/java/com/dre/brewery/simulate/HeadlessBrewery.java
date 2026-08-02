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

import com.dre.brewery.BreweryPlugin;
import com.dre.brewery.configuration.ConfigManager;
import com.dre.brewery.configuration.configurer.TranslationManager;
import com.dre.brewery.configuration.files.CauldronFile;
import com.dre.brewery.configuration.files.Config;
import com.dre.brewery.configuration.files.Lang;
import com.dre.brewery.configuration.files.RecipesFile;
import com.dre.brewery.integration.bstats.BreweryStats;
import com.dre.brewery.recipe.BCauldronRecipe;
import com.dre.brewery.recipe.BRecipe;
import com.dre.brewery.recipe.CustomItem;
import com.dre.brewery.recipe.PluginItem;
import com.dre.brewery.recipe.SimpleItem;
import org.jetbrains.annotations.Nullable;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Boots just enough of BreweryX to brew, with no server, no database and no network.
 * <p>
 * The plugin is loaded but deliberately <em>not</em> enabled: {@link BreweryPlugin#onEnable()}
 * builds a {@code DataManager} that defaults to SQLite (whose driver the server supplies, not
 * Gradle), registers listeners, schedules heartbeats and calls out to bStats and the release
 * checker. None of that is needed to brew, and most of it does not work headlessly.
 * <p>
 * What follows in {@link #start} is therefore a hand-maintained subset of {@code onEnable}. If
 * that method grows a step the simulation depends on, this one has to grow it too.
 */
public final class HeadlessBrewery {

    /** Config files copied out of a real server's plugin folder, when one is given. */
    private static final List<String> CONFIG_FILES =
        List.of("config.yml", "recipes.yml", "cauldron.yml", "custom-items.yml");

    private static boolean started = false;
    private static BreweryPlugin plugin;
    private static String configSource = "BreweryX defaults";

    private HeadlessBrewery() {
    }

    /**
     * Starts the mock server and loads BreweryX's recipes.
     *
     * @param sourceConfigDir a real {@code plugins/BreweryX} folder to brew against, or null to
     *                        use BreweryX's built-in defaults
     * @throws BootstrapException if the config folder or the recipes in it cannot be used
     */
    public static synchronized void start(@Nullable Path sourceConfigDir) {
        if (started) {
            return;
        }

        if (sourceConfigDir != null) {
            validateConfigDir(sourceConfigDir);
            configSource = sourceConfigDir.toAbsolutePath().toString();
        }

        ServerMock server = MockBukkit.mock();
        started = true;

        // loadPlugin() reads plugin.yml off the classpath and runs onLoad(), but not onEnable().
        plugin = (BreweryPlugin) server.getPluginManager().loadPlugin(BreweryPlugin.class);
        if (BreweryPlugin.getInstance() != plugin) {
            throw new BootstrapException(
                "BreweryPlugin.getInstance() does not point at the plugin MockBukkit just loaded. "
                    + "MockBukkit's proxy class loader has forked the plugin's static state, so the "
                    + "config singletons this fixture sets up are not the ones the brewing code reads.");
        }

        // Seed the mock data folder before the first ConfigManager call, which is what constructs
        // ConfigHead against it and lets Okaeri write its defaults. Everything is copied, so the
        // real server's files are never rewritten.
        if (sourceConfigDir != null) {
            copyConfig(sourceConfigDir, plugin.getDataFolder().toPath());
            TranslationManager.newInstance(plugin.getDataFolder()); // re-read `language`
        }

        // Everything below mirrors BreweryPlugin.onEnable(), in the same order.
        CustomItem.registerItemLoader(plugin);
        SimpleItem.registerItemLoader(plugin);
        PluginItem.registerItemLoader(plugin);

        Config config = ConfigManager.getConfig(Config.class);
        if (config.isFirstCreation()) {
            config.onFirstCreation();
        }

        TranslationManager.getInstance().updateTranslationFiles();
        ConfigManager.newInstance(Lang.class, false);

        ConfigManager.registerDefaultPluginItems();
        ConfigManager.loadCauldronIngredients();
        ConfigManager.loadRecipes();
        ConfigManager.loadSeed();

        // BIngredients.cook() calls plugin.getBreweryStats().metricsForCreate(false), and the
        // field is only assigned in onEnable().
        setBreweryStats(plugin, new BreweryStats());

        assertEverythingLoaded();
    }

    /**
     * Tears the mock server down. Safe to call when {@link #start} was never called.
     */
    public static synchronized void shutdown() {
        if (started) {
            MockBukkit.unmock();
            started = false;
            plugin = null;
            configSource = "BreweryX defaults";
        }
    }

    /**
     * @return a description of where the recipes came from, for error messages
     */
    public static String configSource() {
        return configSource;
    }

    private static void validateConfigDir(Path dir) {
        if (!Files.exists(dir)) {
            throw new BootstrapException("No such config folder: " + dir.toAbsolutePath());
        }
        if (!Files.isDirectory(dir)) {
            throw new BootstrapException("Config path is not a folder: " + dir.toAbsolutePath());
        }
        boolean anyPresent = CONFIG_FILES.stream().anyMatch(name -> Files.exists(dir.resolve(name)));
        if (!anyPresent) {
            throw new BootstrapException(
                "Config folder " + dir.toAbsolutePath() + " holds none of " + String.join(", ", CONFIG_FILES)
                    + ". It should point at a server's plugins/BreweryX folder.");
        }
    }

    private static void copyConfig(Path from, Path to) {
        try {
            Files.createDirectories(to);
            for (String name : CONFIG_FILES) {
                Path source = from.resolve(name);
                if (Files.exists(source)) {
                    Files.copy(source, to.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            copyDirectory(from.resolve("languages"), to.resolve("languages"));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not copy the config from " + from.toAbsolutePath(), e);
        }
    }

    private static void copyDirectory(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            return;
        }
        try (Stream<Path> entries = Files.walk(from)) {
            List<Path> paths = entries.sorted(Comparator.naturalOrder()).toList();
            for (Path path : paths) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * {@code ConfigManager.loadRecipes()} logs and drops recipes that fail to load, which would
     * otherwise show up much later as a baffling "unknown recipe" inside an unrelated test case.
     * Catch it here instead, while we can still name the ids.
     */
    private static void assertEverythingLoaded() {
        List<String> problems = new ArrayList<>();

        Set<String> loadedRecipes = new HashSet<>();
        for (BRecipe recipe : BRecipe.getConfigRecipes()) {
            loadedRecipes.add(recipe.getId());
        }
        List<String> failedRecipes = ConfigManager.getConfig(RecipesFile.class).getRecipes().keySet().stream()
            .filter(id -> !loadedRecipes.contains(id))
            .sorted()
            .toList();
        if (!failedRecipes.isEmpty()) {
            problems.add("recipes that failed to load: " + String.join(", ", failedRecipes));
        }

        Set<String> loadedCauldrons = new HashSet<>();
        for (BCauldronRecipe recipe : BCauldronRecipe.getConfigRecipes()) {
            loadedCauldrons.add(recipe.getId());
        }
        CauldronFile cauldronFile = ConfigManager.getConfig(CauldronFile.class);
        List<String> failedCauldrons = cauldronFile.getCauldronIngredients().keySet().stream()
            .filter(id -> !loadedCauldrons.contains(id))
            .sorted()
            .toList();
        if (!failedCauldrons.isEmpty()) {
            problems.add("cauldron recipes that failed to load: " + String.join(", ", failedCauldrons));
        }

        if (!problems.isEmpty()) {
            throw new BootstrapException("Config at " + configSource + " has "
                + String.join("; ", problems)
                + ". Fix them there — every test case would otherwise report them as unknown recipes.");
        }
    }

    private static void setBreweryStats(BreweryPlugin plugin, BreweryStats stats) {
        try {
            Field field = BreweryPlugin.class.getDeclaredField("breweryStats");
            field.setAccessible(true);
            field.set(plugin, stats);
        } catch (ReflectiveOperationException e) {
            throw new BootstrapException("Could not inject BreweryStats; has the field been renamed?", e);
        }
    }

    /**
     * Something went wrong bringing BreweryX up, before any brewing could happen.
     */
    public static class BootstrapException extends RuntimeException {
        public BootstrapException(String message) {
            super(message);
        }

        public BootstrapException(String message, Throwable cause) {
            super(message, cause);
        }
    }

}
