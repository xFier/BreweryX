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

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs the brew simulations declared in {@code brew-tests.yml}, one JUnit test per case.
 * <p>
 * Driven by the {@code brewTest} Gradle task, which creates the spec file from the bundled
 * template if it is missing. Excluded from the ordinary {@code test} task, since it needs a
 * spec file to have something to do.
 */
class BrewSpecTest {

    /** Where the spec lives. Set by the {@code brewTest} Gradle task. */
    private static final String SPEC_PROPERTY = "brewery.spec.file";
    /** A real plugins/BreweryX folder to brew against, overriding the spec's own `config:`. */
    private static final String CONFIG_PROPERTY = "brewery.config.dir";

    @AfterAll
    static void shutdown() {
        HeadlessBrewery.shutdown();
    }

    @TestFactory
    Stream<DynamicTest> declaredBrews() {
        Path specFile = Path.of(System.getProperty(SPEC_PROPERTY, "brew-tests.yml")).toAbsolutePath();
        BrewSpec spec = BrewSpec.load(specFile);

        HeadlessBrewery.start(configDir(spec));
        System.out.println("Brewing " + spec.cases().size() + " declared case(s) from " + specFile);
        System.out.println("Recipes from: " + HeadlessBrewery.configSource());

        return spec.cases().stream().map(testCase -> DynamicTest.dynamicTest(
            testCase.name(), () -> run(spec, testCase)));
    }

    private static void run(BrewSpec spec, BrewSpec.Case testCase) {
        SimulationParameters parameters = spec.resolve(testCase);
        SimulationResult result = BrewSimulator.simulate(parameters);

        List<String> mismatches = BrewExpectation.check(result, testCase.expect());
        if (mismatches.isEmpty()) {
            return;
        }

        StringBuilder message = new StringBuilder();
        message.append(testCase.name()).append('\n');
        message.append("  inputs: ").append(testCase.describeInputs()).append('\n');
        for (String mismatch : mismatches) {
            message.append("  ").append(mismatch).append('\n');
        }
        message.append('\n');
        BrewExpectation.describe(result).lines()
            .forEach(line -> message.append("  ").append(line).append('\n'));

        fail(message.toString());
    }

    /**
     * The Gradle property wins over the spec's own {@code config:} key, so that a spec can be
     * pointed at a different server without editing it.
     */
    private static @Nullable Path configDir(BrewSpec spec) {
        String override = System.getProperty(CONFIG_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath();
        }
        return spec.configDir();
    }

}
