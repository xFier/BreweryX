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
import com.dre.brewery.recipe.BCauldronRecipe;
import com.dre.brewery.recipe.BRecipe;
import com.dre.brewery.recipe.RecipeItem;
import com.dre.brewery.simulate.BrewRule.Result;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The fixed set of checks {@code brewReport} runs against every recipe and cauldron brew.
 * <p>
 * Every rule derives both its inputs and its expectations from the subject's own numbers, using
 * {@link RecipeMath} rather than the engine. What each one expects is chosen to be exactly
 * derivable and nothing more:
 * <ul>
 *     <li>Rules that stay inside a recipe's tolerances expect the recipe still matches and pin the
 *         quality, because the arithmetic gives one answer.</li>
 *     <li>Rules that step outside them expect only {@code notRecipe}. Past its tolerance the recipe
 *         is rejected outright, but whether some <em>other</em> recipe steps in to claim those
 *         ingredients depends on the rest of the config, which is what the report's overlaps are
 *         for.</li>
 * </ul>
 * Age and wood boundaries are deliberately probed at half tolerance and well beyond it rather than
 * exactly on it: unlike the cook and count boundaries, which are whole numbers, the age tolerance
 * is {@code 20 / difficulty} and lands on a knife-edge where a float rounding either way flips the
 * factor between 0 and −1.
 * <p>
 * Each case carries a description in plain words, since that is what the report prints. Rules
 * that cannot say anything about a subject skip themselves; a skip only carries a reason when the
 * reason tells a recipe author something ("any barrel wood works"), not when it is obvious from
 * the recipe ("it is never distilled").
 */
final class BrewRules {

    /** Items to stand in for a removed ingredient. */
    private static final List<String> FILLERS =
        List.of("DIRT", "COBBLESTONE", "GRAVEL", "FLINT", "STRING", "BONE");

    /** Cook time for ferment cases: a ferment is only reported when no recipe claimed the cook. */
    private static final int FERMENT_COOK_TIME = 1;

    private static final List<BrewRule> RULES = new ArrayList<>();

    private BrewRules() {
    }

    static {
        // ------------------------------------------------------------ brews
        register(recipeRule("perfect", BrewRules::perfect));

        register(recipeRule("cook-shortest", recipe -> cookCase(recipe, "cook-shortest",
            recipe.getCookingTime() - RecipeMath.cookTolerance(recipe), true, "shortest that should work")));
        register(recipeRule("cook-too-short", recipe -> cookCase(recipe, "cook-too-short",
            recipe.getCookingTime() - RecipeMath.cookTolerance(recipe) - 1, false, "just too short")));
        register(recipeRule("cook-longest", recipe -> cookCase(recipe, "cook-longest",
            recipe.getCookingTime() + RecipeMath.cookTolerance(recipe), true, "longest that should work")));
        register(recipeRule("cook-too-long", recipe -> cookCase(recipe, "cook-too-long",
            recipe.getCookingTime() + RecipeMath.cookTolerance(recipe) + 1, false, "just too long")));

        register(recipeRule("count-one-under", recipe -> countOneOff(recipe, "count-one-under", -1)));
        register(recipeRule("count-one-over", recipe -> countOneOff(recipe, "count-one-over", 1)));
        register(recipeRule("count-limit", recipe -> countCase(recipe, "count-limit",
            firstCountTolerance(recipe), true, "most that should work")));
        register(recipeRule("count-beyond-limit", recipe -> countCase(recipe, "count-beyond-limit",
            firstCountTolerance(recipe) + 1, false, "just too many")));

        register(recipeRule("ingredient-missing", BrewRules::ingredientMissing));

        register(recipeRule("distill-none", BrewRules::distillNone));
        register(recipeRule("distill-fewer", recipe -> distillCountCase(recipe, "distill-fewer", -1)));
        register(recipeRule("distill-more", recipe -> distillCountCase(recipe, "distill-more", 1)));
        register(recipeRule("distill-unwanted", BrewRules::distillUnwanted));

        register(recipeRule("age-under",
            recipe -> ageCase(recipe, "age-under", -RecipeMath.ageTolerance(recipe) / 2)));
        register(recipeRule("age-over",
            recipe -> ageCase(recipe, "age-over", RecipeMath.ageTolerance(recipe) / 2)));
        register(recipeRule("age-beyond",
            recipe -> ageCase(recipe, "age-beyond", RecipeMath.ageTolerance(recipe) + 1)));
        register(recipeRule("age-none", BrewRules::ageNone));
        register(recipeRule("age-unwanted", BrewRules::ageUnwanted));

        register(recipeRule("wood-neighbour", recipe -> woodCase(recipe, "wood-neighbour", false)));
        register(recipeRule("wood-worst", recipe -> woodCase(recipe, "wood-worst", true)));

        // ------------------------------------------------------------ ferments
        register(fermentRule("ferment-exact", ferment -> fermentCase(ferment, "ferment-exact",
            amount -> amount, "exactly its ingredients")));
        register(fermentRule("ferment-abundant", ferment -> fermentCase(ferment, "ferment-abundant",
            amount -> amount * 2, "double of every ingredient")));
        register(fermentRule("ferment-sparse", ferment -> fermentCase(ferment, "ferment-sparse",
            amount -> 1, "one of each ingredient")));
        register(fermentRule("ferment-missing", BrewRules::fermentMissing));
    }

    /** Every rule, in report order. */
    static List<BrewRule> all() {
        return Collections.unmodifiableList(RULES);
    }

    private static void register(BrewRule rule) {
        RULES.add(rule);
    }

    // ---------------------------------------------------------------- brew rules

    private static Result perfect(BRecipe recipe) {
        int runs = recipe.needsDistilling() ? recipe.getDistillruns() : 0;
        Draft draft = draft(recipe, "perfect").describe("brewed exactly as written");
        draft.expect("recipe", id(recipe));
        draft.expect("quality", 10);
        draft.expect("defects", List.of());
        draft.expect("distillRuns", runs);
        draft.expect("alcohol", RecipeMath.alcohol(recipe, 10, runs));
        draft.expect("ageTime", recipe.needsToAge() ? recipe.getAge() : 0);
        if (recipe.needsToAge() && !recipe.usesAnyWood()) {
            draft.expect("wood", recipe.getWood().name());
        }
        return draft.done();
    }

    private static Result cookCase(BRecipe recipe, String ruleName, int cookedTime, boolean shouldMatch,
                                   String boundary) {
        if (cookedTime < 1) {
            return skip("Cooking it for as little as 1 minute still works.");
        }
        Draft draft = draft(recipe, ruleName).cook(cookedTime)
            .describe("cooked " + cookedTime + " min (" + boundary + ")");
        if (shouldMatch) {
            draft.expect("recipe", id(recipe));
            draft.expect("quality", quality(recipe, 10, cookQuality(recipe, cookedTime)));
            draft.expect("worstDefect",
                String.format("CookTimeMismatch{%d/%d}", cookedTime, recipe.getCookingTime()));
        } else {
            draft.expect("notRecipe", id(recipe));
        }
        return draft.done();
    }

    /**
     * A single item off. At a tolerance of ±1 that is already the boundary, so this would duplicate
     * count-limit and skips instead.
     */
    private static Result countOneOff(BRecipe recipe, String ruleName, int offset) {
        if (recipe.getIngredients().isEmpty() || firstCountTolerance(recipe) == 1) {
            return quiet();
        }
        return countCase(recipe, ruleName, offset, true, null);
    }

    private static Result countCase(BRecipe recipe, String ruleName, int offset, boolean shouldMatch,
                                    @Nullable String boundary) {
        if (recipe.getIngredients().isEmpty()) {
            return quiet();
        }
        RecipeItem first = recipe.getIngredients().get(0);
        int wanted = first.getAmount();
        int amount = wanted + offset;

        if (amount < 1) {
            return quiet();
        }
        List<String> ingredients = ingredientStrings(recipe, 0, amount);
        if (ingredients == null) {
            return skip(NOT_WRITABLE);
        }

        String description = amount + " " + item(first) + " instead of " + wanted;
        Draft draft = draft(recipe, ruleName).ingredients(ingredients)
            .describe(boundary == null ? description : description + " (" + boundary + ")");
        if (shouldMatch) {
            int ingredientQuality = RecipeMath.factorQuality(
                RecipeMath.countDeduction(recipe, wanted, amount));
            draft.expect("recipe", id(recipe));
            draft.expect("quality", quality(recipe, ingredientQuality, 10));
        } else {
            draft.expect("notRecipe", id(recipe));
        }
        return draft.done();
    }

    private static Result ingredientMissing(BRecipe recipe) {
        if (recipe.getIngredients().isEmpty()) {
            return quiet();
        }
        List<String> ingredients = ingredientStrings(recipe, -1, 0);
        if (ingredients == null) {
            return skip(NOT_WRITABLE);
        }
        RecipeItem dropped = recipe.getIngredients().get(0);
        ingredients = new ArrayList<>(ingredients);
        ingredients.remove(0);
        if (ingredients.isEmpty()) {
            // An empty list would fall back to the recipe's own ingredients and test nothing, so
            // put something the recipe does not use in the pot instead.
            String filler = filler(recipe.getIngredients());
            if (filler == null) {
                return quiet();
            }
            ingredients = List.of(filler + "/1");
        }

        return draft(recipe, "ingredient-missing")
            .ingredients(ingredients)
            .describe("left out the " + item(dropped))
            .expect("notRecipe", id(recipe))
            .done();
    }

    private static Result distillNone(BRecipe recipe) {
        if (!recipe.needsDistilling()) {
            return quiet();
        }
        return draft(recipe, "distill-none")
            .distill(0)
            .describe("never distilled")
            .expect("notRecipe", id(recipe))
            .done();
    }

    private static Result distillCountCase(BRecipe recipe, String ruleName, int offset) {
        if (!recipe.needsDistilling()) {
            return quiet();
        }
        int runs = recipe.getDistillruns() + offset;
        if (runs < 1) {
            return quiet();
        }
        // Distill runs move alcohol, never quality: the still's contribution to quality is only
        // whether the brew went through it at all.
        return draft(recipe, ruleName)
            .distill(runs)
            .describe("distilled " + times(runs) + " instead of " + times(recipe.getDistillruns()))
            .expect("recipe", id(recipe))
            .expect("quality", 10)
            .expect("distillRuns", runs)
            .expect("alcohol", RecipeMath.alcohol(recipe, 10, runs))
            .done();
    }

    /**
     * The still has two ways of dealing with a recipe that never asked for it, and this expects the
     * one thing true of both. Either nothing claims the brew, which zeroes the quality but leaves
     * {@code currentRecipe} pointing at whatever the cauldron named it — a ruined distill does not
     * clear the recipe, where a ruined ageing does — or some genuinely distillable recipe claims
     * the ingredients instead and hands back a poor version of itself. Distilling a beer mash, for
     * instance, gives a bad whiskey rather than nothing at all.
     * <p>
     * So neither {@code notRecipe} nor {@code quality: 0} holds on its own. What does hold is that
     * the still cannot produce a <em>good</em> brew from a recipe that is not meant to see it; a
     * quality of 10 here would mean two recipes share ingredients and a cook time.
     */
    private static Result distillUnwanted(BRecipe recipe) {
        if (recipe.needsDistilling()) {
            return quiet();
        }
        // No barrel either, so the run ends at the still rather than giving the barrel a second
        // chance to name the brew.
        return draft(recipe, "distill-unwanted")
            .distill(1)
            .noAge()
            .describe("distilled once, though the recipe never is")
            .expect("quality", "0..9")
            .done();
    }

    private static Result ageCase(BRecipe recipe, String ruleName, float offset) {
        if (!recipe.needsToAge()) {
            return quiet();
        }
        if (recipe.getDifficulty() == 0) {
            return skip(DIFFICULTY_ZERO);
        }
        float years = recipe.getAge() + offset;
        if (years <= 0.5f) {
            return quiet();
        }

        BarrelWoodType wood = barrelWood(recipe);
        int ageQuality = RecipeMath.factorQuality(RecipeMath.ageDeduction(recipe, years));
        return draft(recipe, ruleName)
            .age(wood, years)
            .describe("aged " + years(years) + " instead of " + years(recipe.getAge()))
            .expect("recipe", id(recipe))
            .expect("quality", RecipeMath.finalQuality(10, 10, 10, ageQuality))
            .expect("ageTime", years)
            .done();
    }

    private static Result ageNone(BRecipe recipe) {
        if (!recipe.needsToAge()) {
            return quiet();
        }
        Draft draft = draft(recipe, "age-none").noAge()
            .describe("never put in a barrel")
            .expect("ageTime", 0);

        if (!recipe.needsDistilling()) {
            // Cooking alone can only produce a cooking-only recipe, which this is not.
            return draft.expect("notRecipe", id(recipe)).done();
        }

        // A distilled recipe is still claimed by the still, just badly: an unbarrelled brew keeps
        // the wood it was born with, which is ANY, and is short of the barrel by its whole age.
        int woodQuality = RecipeMath.factorQuality(
            RecipeMath.woodDeduction(recipe, BarrelWoodType.ANY));
        int ageQuality = RecipeMath.factorQuality(RecipeMath.ageDeduction(recipe, 0));
        return draft
            .expect("recipe", id(recipe))
            .expect("quality", RecipeMath.finalQuality(10, 10, woodQuality, ageQuality))
            .done();
    }

    private static Result ageUnwanted(BRecipe recipe) {
        if (recipe.needsToAge()) {
            return quiet();
        }
        return draft(recipe, "age-unwanted")
            .age(BarrelWoodType.OAK, 5)
            .describe("aged 5 years in Oak, though the recipe never is")
            .expect("notRecipe", id(recipe))
            .expect("ageTime", 5)
            .done();
    }

    private static Result woodCase(BRecipe recipe, String ruleName, boolean furthest) {
        if (!recipe.needsToAge()) {
            return quiet();
        }
        if (recipe.usesAnyWood()) {
            return skip("Any barrel wood works.");
        }
        if (recipe.getDifficulty() == 0) {
            return skip(DIFFICULTY_ZERO);
        }

        BarrelWoodType wood = furthest ? furthestWood(recipe) : neighbourWood(recipe);
        if (wood == null) {
            return quiet();
        }

        int woodQuality = RecipeMath.factorQuality(RecipeMath.woodDeduction(recipe, wood));
        return draft(recipe, ruleName)
            .age(wood, recipe.getAge())
            .describe("aged in " + wood.getFormattedName() + " instead of " + recipe.getWood().getFormattedName())
            .expect("recipe", id(recipe))
            .expect("quality", RecipeMath.finalQuality(10, 10, woodQuality, 10))
            .expect("wood", wood.name())
            .done();
    }

    // ---------------------------------------------------------------- ferment rules

    private static Result fermentCase(BCauldronRecipe ferment, String ruleName,
                                      Function<Integer, Integer> amounts, String description) {
        List<String> ingredients = fermentIngredients(ferment, amounts);
        if (ingredients == null) {
            return skip(NOT_WRITABLE);
        }
        return new Draft(fermentId(ferment), ruleName)
            .ingredients(ingredients)
            .cook(FERMENT_COOK_TIME)
            .describe(description)
            .expect("cauldronRecipe", fermentId(ferment))
            .done();
    }

    private static Result fermentMissing(BCauldronRecipe ferment) {
        if (ferment.getIngredients().isEmpty()) {
            return quiet();
        }
        List<String> ingredients = fermentIngredients(ferment, amount -> amount);
        if (ingredients == null) {
            return skip(NOT_WRITABLE);
        }
        RecipeItem dropped = ferment.getIngredients().get(0);
        ingredients = new ArrayList<>(ingredients);
        ingredients.remove(0);
        if (ingredients.isEmpty()) {
            String filler = filler(ferment.getIngredients());
            if (filler == null) {
                return quiet();
            }
            ingredients = List.of(filler + "/1");
        }

        return new Draft(fermentId(ferment), "ferment-missing")
            .ingredients(ingredients)
            .cook(FERMENT_COOK_TIME)
            .describe("left out the " + item(dropped))
            .expect("notCauldronRecipe", fermentId(ferment))
            .done();
    }

    // ---------------------------------------------------------------- derivation helpers

    private static final String NOT_WRITABLE =
        "Some ingredients come from other plugins, so the ingredient checks were left out.";

    private static final String DIFFICULTY_ZERO =
        "Difficulty 0, so barrel time and wood make no difference to quality.";

    /**
     * The brew's quality when only the ingredient and cook factors are off. Wood and age are
     * perfect, which for a recipe that never sees a barrel means they do not count at all.
     */
    private static int quality(BRecipe recipe, int ingredientQuality, int cookQuality) {
        return recipe.needsToAge()
            ? RecipeMath.finalQuality(ingredientQuality, cookQuality, 10, 10)
            : RecipeMath.finalQuality(ingredientQuality, cookQuality);
    }

    private static int cookQuality(BRecipe recipe, int cookedTime) {
        return RecipeMath.factorQuality(RecipeMath.cookDeduction(recipe, cookedTime));
    }

    private static int firstCountTolerance(BRecipe recipe) {
        if (recipe.getIngredients().isEmpty()) {
            return 1;
        }
        return RecipeMath.countTolerance(recipe, recipe.getIngredients().get(0).getAmount());
    }

    /** The barrel a recipe is aged in, matching how {@link Age#of} picks one. */
    private static BarrelWoodType barrelWood(BRecipe recipe) {
        return recipe.usesAnyWood() ? BarrelWoodType.OAK : recipe.getWood();
    }

    /** A wood exactly one index step from the nearest one the recipe accepts. */
    private static @Nullable BarrelWoodType neighbourWood(BRecipe recipe) {
        for (BarrelWoodType wood : BarrelWoodType.values()) {
            if (wood.isSpecific() && RecipeMath.woodDiff(recipe, wood) == 1.0f) {
                return wood;
            }
        }
        return null;
    }

    /** The wood furthest in index from every one the recipe accepts. */
    private static @Nullable BarrelWoodType furthestWood(BRecipe recipe) {
        BarrelWoodType worst = null;
        float worstDiff = 0;
        for (BarrelWoodType wood : BarrelWoodType.values()) {
            if (!wood.isSpecific()) {
                continue;
            }
            float diff = RecipeMath.woodDiff(recipe, wood);
            if (diff > worstDiff) {
                worstDiff = diff;
                worst = wood;
            }
        }
        return worst;
    }

    /**
     * The recipe's ingredients as spec strings, optionally overriding one amount.
     *
     * @param recipe the recipe
     * @param index  which ingredient to override, or −1 for none
     * @param amount the amount to give it
     * @return the strings, or null if any ingredient cannot be written back out
     */
    private static @Nullable List<String> ingredientStrings(BRecipe recipe, int index, int amount) {
        return writeOut(recipe.getIngredients(), index, amount);
    }

    private static @Nullable List<String> fermentIngredients(BCauldronRecipe ferment,
                                                             Function<Integer, Integer> amounts) {
        List<String> written = new ArrayList<>();
        for (RecipeItem ingredient : ferment.getIngredients()) {
            String item = writeOut(ingredient, Math.max(1, amounts.apply(ingredient.getAmount())));
            if (item == null) {
                return null;
            }
            written.add(item);
        }
        return written;
    }

    private static @Nullable List<String> writeOut(List<RecipeItem> ingredients, int index, int amount) {
        List<String> written = new ArrayList<>();
        for (int i = 0; i < ingredients.size(); i++) {
            RecipeItem ingredient = ingredients.get(i);
            String item = writeOut(ingredient, i == index ? amount : ingredient.getAmount());
            if (item == null) {
                return null;
            }
            written.add(item);
        }
        return written;
    }

    /**
     * Writes one ingredient out the way {@code recipes.yml} would, and checks it can be read back.
     * Custom and plugin items generally cannot survive the round trip here, since the plugins that
     * define them are not running.
     */
    private static @Nullable String writeOut(RecipeItem ingredient, int amount) {
        String written;
        try {
            written = ingredient.toConfigStringNoAmount() + "/" + amount;
        } catch (IllegalStateException e) {
            return null;
        }
        return BRecipe.loadIngredientVerbose(written) instanceof BRecipe.IngredientResult.Success
            ? written
            : null;
    }

    /** The first stand-in item that is not already one of these ingredients. */
    private static @Nullable String filler(List<RecipeItem> ingredients) {
        Set<String> used = new LinkedHashSet<>();
        for (RecipeItem ingredient : ingredients) {
            try {
                used.add(ingredient.toConfigStringNoAmount().toUpperCase(Locale.ROOT));
            } catch (IllegalStateException ignored) {
                // Not writable, so it cannot collide with a plain material name either.
            }
        }
        for (String candidate : FILLERS) {
            if (!used.contains(candidate)
                && BRecipe.loadIngredientVerbose(candidate + "/1") instanceof BRecipe.IngredientResult.Success) {
                return candidate;
            }
        }
        return null;
    }

    /** An ingredient as a reader would name it: {@code golden_carrot} becomes "golden carrot". */
    static String item(RecipeItem ingredient) {
        return ingredient.getDebugID().replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private static String times(int runs) {
        return runs == 1 ? "once" : runs == 2 ? "twice" : runs + " times";
    }

    static String years(float years) {
        return trim(years) + (years == 1 ? " year" : " years");
    }

    private static String id(BRecipe recipe) {
        return new RuleSubject.Recipe(recipe).id();
    }

    private static String fermentId(BCauldronRecipe ferment) {
        return new RuleSubject.Ferment(ferment).id();
    }

    private static Result skip(String reason) {
        return new Result.Skipped(reason);
    }

    /** A skip that is obvious from the recipe itself, so the report says nothing about it. */
    private static Result quiet() {
        return new Result.Skipped(null);
    }

    private static Draft draft(BRecipe recipe, String ruleName) {
        return new Draft(id(recipe), ruleName).recipe(id(recipe));
    }

    // ---------------------------------------------------------------- plumbing

    private static BrewRule recipeRule(String name, Function<BRecipe, Result> body) {
        return new Adapter(name, RuleSubject.Recipe.class,
            subject -> body.apply(((RuleSubject.Recipe) subject).recipe()));
    }

    private static BrewRule fermentRule(String name, Function<BCauldronRecipe, Result> body) {
        return new Adapter(name, RuleSubject.Ferment.class,
            subject -> body.apply(((RuleSubject.Ferment) subject).recipe()));
    }

    private record Adapter(String name, Class<? extends RuleSubject> kind,
                           Function<RuleSubject, Result> body) implements BrewRule {
        @Override
        public boolean handles(RuleSubject subject) {
            return kind.isInstance(subject);
        }

        @Override
        public Result apply(RuleSubject subject) {
            return body.apply(subject);
        }
    }

    /**
     * Collects a rule's inputs and expectations into a {@link BrewSpec.Case}, so that generated
     * cases are brewed and checked exactly like hand-written ones.
     */
    private static final class Draft {
        private final String subjectId;
        private final String ruleName;
        private final Map<String, Object> expect = new LinkedHashMap<>();
        private String description = "";
        private @Nullable String recipeId;
        private List<String> ingredients = List.of();
        private @Nullable Integer cook;
        private @Nullable Integer distill;
        private @Nullable List<BrewSpec.AgeStep> age;

        Draft(String subjectId, String ruleName) {
            this.subjectId = subjectId;
            this.ruleName = ruleName;
        }

        Draft recipe(String id) {
            this.recipeId = id;
            return this;
        }

        Draft ingredients(List<String> items) {
            this.ingredients = List.copyOf(items);
            return this;
        }

        Draft cook(int minutes) {
            this.cook = minutes;
            return this;
        }

        Draft distill(int runs) {
            this.distill = runs;
            return this;
        }

        Draft age(BarrelWoodType wood, float years) {
            this.age = List.of(new BrewSpec.AgeStep(wood.name(), years));
            return this;
        }

        Draft noAge() {
            this.age = List.of();
            return this;
        }

        /** What was done to the brew, in the words the report prints. */
        Draft describe(String text) {
            this.description = text;
            return this;
        }

        Draft expect(String key, Object value) {
            expect.put(key, value);
            return this;
        }

        Result done() {
            BrewSpec.Case testCase = new BrewSpec.Case(
                subjectId + " / " + ruleName, recipeId, ingredients, cook, distill, age,
                Collections.unmodifiableMap(new LinkedHashMap<>(expect)));
            return new Result.Applied(testCase, description);
        }
    }

    private static String trim(float value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

}
