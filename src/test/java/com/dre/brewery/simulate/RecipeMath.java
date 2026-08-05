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
import com.dre.brewery.configuration.ConfigManager;
import com.dre.brewery.configuration.files.Config;
import com.dre.brewery.recipe.BRecipe;

/**
 * What the brewing engine <em>should</em> produce for a recipe, worked out from that recipe's own
 * cook time, difficulty and ingredient amounts.
 * <p>
 * This deliberately re-implements the formulas rather than calling into {@link
 * com.dre.brewery.BIngredients}: a test that asks the engine what it expects can never disagree
 * with it. {@code ToleranceParityTest} pins the two together for the tolerance functions, so a
 * change to either side surfaces as one explicit failure instead of silently moving every
 * generated input.
 * <p>
 * The formulas are, with {@code d} = difficulty:
 * <ul>
 *     <li>tolerance, for both cook time and ingredient counts:
 *         {@code max(1, round((11 − d) · max(x, 8) / 10))}</li>
 *     <li>cook and count deductions: {@code 10 · |Δ| / tolerance}, so being off by exactly the
 *         tolerance costs the whole 10 points</li>
 *     <li>age deduction: {@code |Δ| · d / 2}, reaching 10 at {@code Δ = 20 / d}</li>
 *     <li>wood deduction: {@code woodDiff · d} under the legacy algorithm</li>
 * </ul>
 * A factor's quality is {@code round(10 − deductions)}, floored at −1, and the brew's quality is
 * the mean of the two or four factors, rounded.
 */
final class RecipeMath {

    /** Deduction applied for any single defect that costs a factor all of its quality. */
    static final float FULL_DEDUCTION = 10.0f;

    private RecipeMath() {
    }

    // ---------------------------------------------------------------- tolerances

    /**
     * The furthest a cook time can stray from the recipe and still match it. One minute beyond
     * this the cooking evaluation turns fatal and the recipe is rejected outright.
     *
     * @param recipe the recipe
     * @return the tolerance in minutes, at least 1
     */
    static int cookTolerance(BRecipe recipe) {
        return tolerance(recipe.getCookingTime(), recipe.getDifficulty());
    }

    /**
     * The furthest an ingredient count can stray from the recipe and still match it.
     *
     * @param recipe the recipe
     * @param amount the amount the recipe asks for
     * @return the tolerance in items, at least 1
     */
    static int countTolerance(BRecipe recipe, int amount) {
        return tolerance(amount, recipe.getDifficulty());
    }

    /**
     * Amounts and times below 8 are all treated as 8, so even a one-item recipe gets a usable
     * tolerance.
     */
    private static int tolerance(int value, int difficulty) {
        int scaled = Math.max(value, 8);
        int allowed = Math.round((float) ((11.0 - difficulty) * (scaled / 10.0)));
        return Math.max(allowed, 1);
    }

    /**
     * How far off the recipe's age an aged brew can be before the age factor is spent. Unlike the
     * cook and count tolerances this is not a hard boundary: age can never reject a recipe, it can
     * only floor the factor at −1.
     *
     * @param recipe the recipe
     * @return the tolerance in Minecraft days, infinite at difficulty 0 where age costs nothing
     */
    static float ageTolerance(BRecipe recipe) {
        int difficulty = recipe.getDifficulty();
        return difficulty == 0 ? Float.POSITIVE_INFINITY : 20.0f / difficulty;
    }

    // ---------------------------------------------------------------- deductions

    /**
     * @return what cooking for {@code cookedTime} minutes costs the recipe
     */
    static float cookDeduction(BRecipe recipe, int cookedTime) {
        if (cookedTime < 1) {
            // The engine short-circuits here rather than building a CookTimeMismatch, which would
            // throw if the recipe also wanted 0 minutes.
            return FULL_DEDUCTION;
        }
        int off = Math.abs(cookedTime - recipe.getCookingTime());
        return off / (float) cookTolerance(recipe) * FULL_DEDUCTION;
    }

    /**
     * @return what using {@code actual} of an ingredient the recipe wants {@code wanted} of costs
     */
    static float countDeduction(BRecipe recipe, int wanted, int actual) {
        int off = Math.abs(actual - wanted);
        return off / (float) countTolerance(recipe, wanted) * FULL_DEDUCTION;
    }

    /**
     * @return what spending {@code ageTime} days in a barrel costs, given the recipe's own age
     */
    static float ageDeduction(BRecipe recipe, float ageTime) {
        return Math.abs(ageTime - recipe.getAge()) * (recipe.getDifficulty() / 2.0f);
    }

    /**
     * What ageing in the wrong wood costs. Recipes accepting any wood never lose anything.
     * <p>
     * Under the legacy algorithm this is the gap in {@link BarrelWoodType#getIndex() wood index}
     * scaled by difficulty. Under the new algorithm it is the complement of the quality documented
     * beside {@code BIngredients.getWoodQualityNew}: distance 0 is perfect and distance 5 is
     * nearly worthless, softened at low difficulty.
     *
     * @param recipe the recipe
     * @param wood   the wood the brew actually aged in
     * @return the deduction
     */
    static float woodDeduction(BRecipe recipe, BarrelWoodType wood) {
        if (recipe.usesAnyWood() || wood == recipe.getWood()) {
            return 0.0f;
        }
        if (newBarrelTypeAlgorithm()) {
            return FULL_DEDUCTION - newWoodQuality(recipe, wood);
        }
        return woodDiff(recipe, wood) * recipe.getDifficulty();
    }

    /**
     * The gap between a wood and the nearest one the recipe accepts, measured in
     * {@link BarrelWoodType#getIndex() wood index}.
     * <p>
     * A recipe listing several woods is only as unhappy as its closest one, so a brew aged in a
     * secondary wood loses nothing even though it is not the primary.
     *
     * @param recipe the recipe
     * @param wood   the wood the brew aged in
     * @return the distance in index steps
     */
    static float woodDiff(BRecipe recipe, BarrelWoodType wood) {
        return (float) recipe.getBarrelTypes().stream()
            .mapToDouble(accepted -> Math.abs(wood.getIndex() - accepted.getIndex()))
            .min()
            .orElse(0.0);
    }

    /**
     * The quality the new algorithm is documented to give, per the table at
     * {@code BIngredients.getWoodQualityNew}.
     */
    private static int newWoodQuality(BRecipe recipe, BarrelWoodType wood) {
        float base = switch (recipe.getWood().getDistance(wood)) {
            case 0 -> 10.0f;
            case 1 -> 9.0f;
            case 2 -> 7.75f;
            case 3 -> 6.25f;
            case 4 -> 4.5f;
            case 5 -> 2.5f;
            default -> 0.0f;
        };
        if (base == 0.0f) {
            return 0;
        }
        return Math.max(Math.round(10.0f - (10.0f - base) * 0.5f * recipe.getDifficulty()), 0);
    }

    static boolean newBarrelTypeAlgorithm() {
        return ConfigManager.getConfig(Config.class).isNewBarrelTypeAlgorithm();
    }

    // ---------------------------------------------------------------- quality

    /**
     * Turns the deductions against one factor into that factor's quality.
     *
     * @param deductions everything that factor lost
     * @return 0-10, or −1 once the deductions pass 10
     */
    static int factorQuality(float deductions) {
        float quality = FULL_DEDUCTION - deductions;
        if (quality < 0) {
            return -1;
        }
        return Math.round(quality);
    }

    /**
     * The quality of a brew that never saw a barrel.
     */
    static int finalQuality(int ingredientQuality, int cookQuality) {
        return Math.round((ingredientQuality + cookQuality) / 2.0f);
    }

    /**
     * The quality of a brew that did.
     */
    static int finalQuality(int ingredientQuality, int cookQuality, int woodQuality, int ageQuality) {
        return Math.round((ingredientQuality + cookQuality + woodQuality + ageQuality) / 4.0f);
    }

    // ---------------------------------------------------------------- alcohol

    /**
     * The alcohol a brew of this recipe ends up with. Mirrors the second half of
     * {@code Brew.calcAlcohol}, including its integer truncation: an odd alcohol on a distilled
     * recipe cannot survive the halving, so a perfect 25 comes out as 24.
     * <p>
     * Only defined for a brew that still matches the recipe at a quality above 0. The engine has a
     * separate consolation path for ruined brews that keys off the brew's own age and whether it
     * kept a recipe at all — neither of which can be read off the recipe — so predicting that here
     * would mean guessing. No rule asserts alcohol on a ruined brew for exactly that reason.
     *
     * @param recipe      the recipe that matched
     * @param quality     the brew's final quality, which must be above 0
     * @param distillRuns how many times it went through the still
     * @return the alcohol content
     * @throws IllegalArgumentException if the quality is 0 or less
     */
    static int alcohol(BRecipe recipe, int quality, int distillRuns) {
        if (quality <= 0) {
            throw new IllegalArgumentException(
                "alcohol() cannot predict a ruined brew (quality " + quality + "); the engine's "
                    + "consolation alcohol depends on state the recipe does not carry");
        }

        int alc = recipe.getAlcohol();
        if (recipe.needsDistilling()) {
            if (distillRuns == 0) {
                return 0;
            }
            alc *= 1 - ((10 - quality) * 0.04f);
            alc /= 2;
            alc *= 1.0f + ((float) distillRuns / recipe.getDistillruns());
        } else {
            alc *= quality / 10.0f;
        }
        return alc;
    }

}
