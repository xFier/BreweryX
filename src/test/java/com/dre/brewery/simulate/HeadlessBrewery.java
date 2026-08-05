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
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.PluginDescriptionFile;
import org.jetbrains.annotations.Nullable;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.IOException;
import java.io.InputStream;
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

        // The data folder has to be seeded before the plugin is *constructed*, not merely before
        // it is enabled: BreweryPlugin's instance initializer calls TranslationManager against
        // getDataFolder(), so Okaeri creates config.yml and holds it open while the constructor
        // runs. Copying afterwards fails outright on Windows, where that open handle makes the
        // file undeletable. Everything is copied, so the real server's files are never touched.
        Path seededDataFolder = sourceConfigDir == null ? null : seedDataFolder(server, sourceConfigDir);

        // loadPlugin() reads plugin.yml off the classpath and runs onLoad(), but not onEnable().
        plugin = (BreweryPlugin) server.getPluginManager().loadPlugin(BreweryPlugin.class);
        if (BreweryPlugin.getInstance() != plugin) {
            throw new BootstrapException(
                "BreweryPlugin.getInstance() does not point at the plugin MockBukkit just loaded. "
                    + "MockBukkit's proxy class loader has forked the plugin's static state, so the "
                    + "config singletons this fixture sets up are not the ones the brewing code reads.");
        }
        assertSeedWasRead(seededDataFolder);

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

    /**
     * Copies the config into the folder MockBukkit is going to hand the plugin, before there is a
     * plugin to hand it to.
     * <p>
     * MockBukkit names a plugin's data folder after its {@code plugin.yml}, so the path can be
     * worked out — and claimed — ahead of the plugin that will use it. That is a guess about
     * MockBukkit's internals, so {@link #assertSeedWasRead} checks it afterwards rather than
     * letting a wrong guess quietly run the whole suite against the built-in recipes.
     *
     * @return the folder that was seeded
     */
    private static Path seedDataFolder(ServerMock server, Path sourceConfigDir) {
        PluginDescriptionFile description = pluginDescription();
        Path dataFolder;
        try {
            dataFolder = server.getPluginManager()
                .createTemporaryDirectory(description.getName() + "-" + description.getVersion())
                .toPath();
        } catch (IOException e) {
            throw new BootstrapException("Could not create a data folder to put the config in", e);
        }
        copyConfig(sourceConfigDir, dataFolder);
        return dataFolder.toAbsolutePath().normalize();
    }

    private static PluginDescriptionFile pluginDescription() {
        try (InputStream in = HeadlessBrewery.class.getClassLoader().getResourceAsStream("plugin.yml")) {
            if (in == null) {
                throw new BootstrapException("No plugin.yml on the classpath to read the plugin's name from.");
            }
            return new PluginDescriptionFile(in);
        } catch (IOException | InvalidDescriptionException e) {
            throw new BootstrapException("Could not read plugin.yml off the classpath", e);
        }
    }

    /**
     * Fails if MockBukkit gave the plugin a different folder than the one the config went into,
     * which would mean the recipes came from BreweryX's defaults while every message claimed
     * otherwise.
     */
    private static void assertSeedWasRead(@Nullable Path seededDataFolder) {
        if (seededDataFolder == null) {
            return;
        }
        Path actual = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        if (!actual.equals(seededDataFolder)) {
            throw new BootstrapException("The config was copied to " + seededDataFolder
                + ", but MockBukkit gave the plugin " + actual + ", so it was never read. "
                + "MockBukkit has changed how it names data folders; seedDataFolder() has to follow.");
        }
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
