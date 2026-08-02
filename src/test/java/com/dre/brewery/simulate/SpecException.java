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

import java.util.List;

/**
 * The brew test spec could not be understood. Carries every problem found, not just the first,
 * so that one run is enough to fix the whole file.
 */
public class SpecException extends RuntimeException {

    private final transient List<String> problems;

    public SpecException(String header, List<String> problems) {
        super(format(header, problems));
        this.problems = List.copyOf(problems);
    }

    public SpecException(String message) {
        super(message);
        this.problems = List.of(message);
    }

    public List<String> getProblems() {
        return problems;
    }

    private static String format(String header, List<String> problems) {
        StringBuilder sb = new StringBuilder(header);
        for (String problem : problems) {
            sb.append("\n  - ").append(problem);
        }
        return sb.toString();
    }

}
