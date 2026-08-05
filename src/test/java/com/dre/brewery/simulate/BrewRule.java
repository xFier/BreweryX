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
 * One way of brewing a recipe wrong — or exactly right — together with what that should produce.
 * <p>
 * A rule works its inputs out from the recipe's own numbers, so the same rule means something
 * different for every recipe: {@code cook-shortest} is 4 minutes for a difficulty-3 recipe that
 * wants 20, and 19 minutes for a difficulty-10 one. When a rule cannot say anything about a recipe
 * — wood rules on a recipe that takes any barrel, age rules where difficulty 0 makes age free — it
 * skips itself and says why, rather than inventing a case.
 */
interface BrewRule {

    /** The name the maintainer selects this rule by. */
    String name();

    /** One line for the documentation and the spec template. */
    String summary();

    /**
     * Whether this rule has anything to say about this kind of subject at all.
     * <p>
     * A preset names the whole vocabulary and lets each rule decide, so this keeps the ferment
     * rules out of every recipe's report and vice versa. It is not the same as skipping: a skip is
     * a rule that could have applied to this subject and did not, which is worth saying.
     */
    boolean handles(RuleSubject subject);

    /**
     * Works out this rule's case for a subject.
     *
     * @param subject the recipe or ferment to derive from
     * @return the case to run, or why there is nothing to run
     */
    Result apply(RuleSubject subject);

    /**
     * Either a case to brew, or a reason there is none.
     */
    sealed interface Result {

        /** A case, ready to go through the ordinary spec pipeline. */
        record Applied(BrewSpec.Case testCase, String inputs) implements Result {
        }

        /** Nothing to run, and why. */
        record Skipped(String reason) implements Result {
        }
    }

}
