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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the recipe report. Driven by the {@code brewReport} Gradle task, and excluded from the
 * ordinary {@code test} task: it describes a config rather than testing code, so it never fails on
 * what it finds.
 */
class BrewReportTest {

    /** A real plugins/BreweryX folder to report on. Set by {@code -PbreweryConfig}. */
    private static final String CONFIG_PROPERTY = "brewery.config.dir";
    /** Where the full report goes. Set by the {@code brewReport} Gradle task. */
    private static final String REPORT_PROPERTY = "brewery.report.file";

    @AfterAll
    static void shutdown() {
        HeadlessBrewery.shutdown();
    }

    @Test
    void report() throws IOException {
        String configDir = System.getProperty(CONFIG_PROPERTY);
        HeadlessBrewery.start(configDir == null || configDir.isBlank() ? null : Path.of(configDir));

        BrewReport report = BrewReport.run();
        Path file = Path.of(System.getProperty(REPORT_PROPERTY, "brew-report.txt")).toAbsolutePath();
        Files.writeString(file, report.full(), StandardCharsets.UTF_8);

        System.out.println();
        System.out.println(report.summary());
        System.out.println("Every brew tried, recipe by recipe: " + file);
    }

}
