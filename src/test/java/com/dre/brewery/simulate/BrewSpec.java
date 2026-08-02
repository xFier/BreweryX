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
import com.dre.brewery.configuration.files.Lang;
import com.dre.brewery.recipe.BRecipe;
import org.bukkit.ChatColor;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The declarative brew tests, as read from {@code brew-tests.yml}.
 * <p>
 * Loading happens in two passes because the first pass has to run before BreweryX is up: the file
 * may name the config folder to brew against. {@link #load} does the structural pass, and
 * {@link #resolve} turns a case's recipe names, ingredients and woods into real objects once the
 * recipes are loaded.
 */
public record BrewSpec(@Nullable Path configDir, List<Case> cases) {

    /** Keys a test case may carry. */
    private static final Set<String> CASE_KEYS =
        new LinkedHashSet<>(List.of("name", "recipe", "ingredients", "cook", "distill", "age", "expect"));
    /** Keys the file itself may carry. */
    private static final Set<String> ROOT_KEYS = new LinkedHashSet<>(List.of("config", "tests"));
    /** Keys an `age` step may carry. */
    private static final Set<String> AGE_KEYS = new LinkedHashSet<>(List.of("wood", "years"));

    /**
     * One declared brew, still in the form the user wrote it.
     */
    public record Case(
        String name,
        @Nullable String recipe,
        List<String> ingredients,
        @Nullable Integer cook,
        @Nullable Integer distill,
        @Nullable List<AgeStep> age,
        Map<String, Object> expect
    ) {
        /**
         * @return the inputs, as a single line, for failure messages
         */
        public String describeInputs() {
            List<String> parts = new ArrayList<>();
            if (recipe != null) {
                parts.add("recipe=" + recipe);
            }
            if (!ingredients.isEmpty()) {
                parts.add("ingredients=" + ingredients);
            }
            if (cook != null) {
                parts.add("cook=" + cook);
            }
            if (distill != null) {
                parts.add("distill=" + distill);
            }
            if (age != null) {
                parts.add("age=" + age);
            }
            return String.join(", ", parts);
        }
    }

    /** One stint in a barrel. */
    public record AgeStep(String wood, float years) {
        @Override
        public String toString() {
            return years + " years in " + wood;
        }
    }

    /**
     * Reads and structurally validates the spec. Does not need BreweryX to be running.
     *
     * @param file the spec file
     * @return the parsed spec
     * @throws SpecException if the file cannot be read or does not make sense
     */
    public static BrewSpec load(Path file) {
        if (!Files.exists(file)) {
            throw new SpecException("No brew test spec at " + file.toAbsolutePath()
                + ". Run `gradlew brewTest` and one will be created for you from the bundled template.");
        }

        Object root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = new Yaml().load(reader);
        } catch (IOException e) {
            throw new SpecException("Could not read " + file.toAbsolutePath() + ": " + e.getMessage());
        } catch (YAMLException e) {
            throw new SpecException(file.toAbsolutePath() + " is not valid YAML: " + e.getMessage());
        }

        String header = "Could not understand " + file.toAbsolutePath() + ":";

        if (root == null) {
            throw new SpecException(file.toAbsolutePath() + " is empty. It should look like:\n"
                + EXAMPLE);
        }
        if (!(root instanceof Map<?, ?> rootMap)) {
            throw new SpecException(file.toAbsolutePath() + " should hold a `tests:` list, but its top level is "
                + describe(root) + ". It should look like:\n" + EXAMPLE);
        }

        List<String> problems = new ArrayList<>();
        for (Object key : rootMap.keySet()) {
            String name = String.valueOf(key);
            if (!ROOT_KEYS.contains(name)) {
                problems.add(Suggestions.unknown("top-level key", name, ROOT_KEYS));
            }
        }

        Path configDir = null;
        Object configValue = rootMap.get("config");
        if (configValue != null) {
            if (configValue instanceof String configPath) {
                Path parsed = Path.of(configPath);
                Path base = file.toAbsolutePath().getParent();
                configDir = parsed.isAbsolute() || base == null ? parsed : base.resolve(parsed).normalize();
            } else {
                problems.add("`config` should be a path to a plugins/BreweryX folder, but is " + describe(configValue));
            }
        }

        Object testsValue = rootMap.get("tests");
        if (testsValue == null) {
            problems.add("no `tests:` list. It should look like:\n" + EXAMPLE);
            throw new SpecException(header, problems);
        }
        if (!(testsValue instanceof List<?> testsList)) {
            problems.add("`tests` should be a list of cases, but is " + describe(testsValue));
            throw new SpecException(header, problems);
        }
        if (testsList.isEmpty()) {
            problems.add("`tests` is empty, so there is nothing to run");
        }

        List<Case> cases = new ArrayList<>();
        for (int i = 0; i < testsList.size(); i++) {
            Object element = testsList.get(i);
            String label = "tests[" + i + "]";
            if (!(element instanceof Map<?, ?> caseMap)) {
                problems.add(label + " should be a case with `expect:`, but is " + describe(element));
                continue;
            }
            Case parsed = parseCase(caseMap, label, problems);
            if (parsed != null) {
                cases.add(parsed);
            }
        }

        if (!problems.isEmpty()) {
            throw new SpecException(header, problems);
        }
        return new BrewSpec(configDir, List.copyOf(cases));
    }

    private static @Nullable Case parseCase(Map<?, ?> map, String label, List<String> problems) {
        String name = map.get("name") instanceof String s ? s : label;
        String where = label.equals(name) ? label : label + " \"" + name + "\"";
        int problemsBefore = problems.size();

        for (Object key : map.keySet()) {
            String keyName = String.valueOf(key);
            if (!CASE_KEYS.contains(keyName)) {
                problems.add(where + ": " + Suggestions.unknown("key", keyName, CASE_KEYS));
            }
        }
        if (map.containsKey("name") && !(map.get("name") instanceof String)) {
            problems.add(where + ": `name` should be text, but is " + describe(map.get("name")));
        }

        String recipe = null;
        Object recipeValue = map.get("recipe");
        if (recipeValue != null) {
            if (recipeValue instanceof String s) {
                recipe = s;
            } else {
                problems.add(where + ": `recipe` should be a recipe id or name, but is " + describe(recipeValue));
            }
        }

        List<String> ingredients = new ArrayList<>();
        Object ingredientsValue = map.get("ingredients");
        if (ingredientsValue != null) {
            if (ingredientsValue instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String s) {
                        ingredients.add(s);
                    } else {
                        problems.add(where + ": every entry of `ingredients` should be text like `WHEAT/3`, but one is "
                            + describe(item));
                    }
                }
            } else {
                problems.add(where + ": `ingredients` should be a list like `[WHEAT/3]`, but is "
                    + describe(ingredientsValue));
            }
        }

        Integer cook = readInt(map.get("cook"), where, "cook", "a whole number of minutes", problems);
        Integer distill = readInt(map.get("distill"), where, "distill", "a whole number of runs", problems);
        if (cook != null && cook < 0) {
            problems.add(where + ": `cook` cannot be negative.");
        }
        if (distill != null && distill < 0) {
            problems.add(where + ": `distill` cannot be negative. Use 0 to say the brew is never distilled.");
        }
        List<AgeStep> age = readAge(map.get("age"), where, problems);

        Map<String, Object> expect = Map.of();
        Object expectValue = map.get("expect");
        if (expectValue == null) {
            problems.add(where + ": no `expect:`, so the case asserts nothing. "
                + "Add at least one of: " + String.join(", ", BrewExpectation.KEYS));
        } else if (expectValue instanceof Map<?, ?> expectMap) {
            expect = BrewExpectation.validate(expectMap, where, problems);
        } else {
            problems.add(where + ": `expect` should be a map of expected values, but is " + describe(expectValue));
        }

        if (problems.size() != problemsBefore) {
            return null;
        }
        return new Case(name, recipe, List.copyOf(ingredients), cook, distill, age, expect);
    }

    private static @Nullable Integer readInt(@Nullable Object value, String where, String key,
                                             String expected, List<String> problems) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Long l && l == l.intValue()) {
            return l.intValue();
        }
        problems.add(where + ": `" + key + "` should be " + expected + ", but is " + describe(value));
        return null;
    }

    private static @Nullable List<AgeStep> readAge(@Nullable Object value, String where, List<String> problems) {
        if (value == null) {
            return null;
        }
        List<?> rawSteps;
        if (value instanceof Map<?, ?> single) {
            rawSteps = List.of(single);
        } else if (value instanceof List<?> list) {
            rawSteps = list;
        } else {
            problems.add(where + ": `age` should be `{wood: OAK, years: 6}`, or a list of those to move the brew "
                + "between barrels, but is " + describe(value));
            return null;
        }

        List<AgeStep> steps = new ArrayList<>();
        for (Object rawStep : rawSteps) {
            if (!(rawStep instanceof Map<?, ?> stepMap)) {
                problems.add(where + ": every `age` step should be `{wood: OAK, years: 6}`, but one is "
                    + describe(rawStep));
                continue;
            }
            for (Object key : stepMap.keySet()) {
                String keyName = String.valueOf(key);
                if (!AGE_KEYS.contains(keyName)) {
                    problems.add(where + ": in `age`, " + Suggestions.unknown("key", keyName, AGE_KEYS));
                }
            }

            Object wood = stepMap.get("wood");
            Object years = stepMap.get("years");
            if (!(wood instanceof String woodName)) {
                problems.add(where + ": `age.wood` should be a barrel wood like OAK, but is " + describe(wood));
                continue;
            }
            if (!(years instanceof Number yearsNumber)) {
                problems.add(where + ": `age.years` should be a number of Minecraft days, but is " + describe(years));
                continue;
            }
            if (yearsNumber.floatValue() <= 0) {
                problems.add(where + ": `age.years` should be more than 0, but is " + yearsNumber
                    + ". Use `age: []` to say the brew is never barrelled.");
                continue;
            }
            steps.add(new AgeStep(woodName, yearsNumber.floatValue()));
        }
        return steps;
    }

    /**
     * Turns a case's recipe names, ingredients and woods into a runnable simulation.
     *
     * @param testCase the case
     * @return the parameters to brew
     * @throws SpecException if anything named in the case does not exist
     */
    public SimulationParameters resolve(Case testCase) {
        List<String> problems = new ArrayList<>();
        SimulationRequest request = new SimulationRequest();

        if (testCase.recipe() != null) {
            BRecipe recipe = BRecipe.getMatching(testCase.recipe());
            if (recipe == null) {
                problems.add("no recipe called '" + testCase.recipe() + "'. "
                    + "Recipes loaded from " + HeadlessBrewery.configSource() + ": " + knownRecipes());
            } else {
                request.recipe(recipe);
            }
        }

        for (String ingredient : testCase.ingredients()) {
            BRecipe.IngredientResult result = BRecipe.loadIngredientVerbose(ingredient);
            if (result instanceof BRecipe.IngredientResult.Error error) {
                problems.add("ingredient '" + ingredient + "': " + describeIngredientError(error));
            } else {
                request.addIngredient(((BRecipe.IngredientResult.Success) result).ingredient());
            }
        }

        if (testCase.cook() != null) {
            request.cookedTime(testCase.cook());
        }
        if (testCase.distill() != null) {
            request.distillRuns(testCase.distill());
        }
        if (testCase.age() != null) {
            if (testCase.age().isEmpty()) {
                request.noAging();
            }
            for (AgeStep step : testCase.age()) {
                BarrelWoodType wood = BarrelWoodType.fromName(step.wood());
                if (wood == null || !wood.isSpecific()) {
                    problems.add("'" + step.wood() + "' is not a barrel wood. Try one of: "
                        + String.join(", ", BarrelWoodType.TAB_COMPLETIONS));
                } else {
                    request.addAgeStep(wood, step.years());
                }
            }
        }

        if (!problems.isEmpty()) {
            throw new SpecException("Case \"" + testCase.name() + "\" cannot be run:", problems);
        }

        return switch (request.resolve()) {
            case SimulationRequest.Resolution.Resolved resolved -> resolved.parameters();
            case SimulationRequest.Resolution.Missing missing -> throw new SpecException(
                switch (missing.field()) {
                    case COOK_TIME -> "Case \"" + testCase.name()
                        + "\" has no `cook:` time, and no `recipe:` to take one from.";
                    case INGREDIENTS -> "Case \"" + testCase.name()
                        + "\" has no `ingredients:`, and no `recipe:` to take them from.";
                });
        };
    }

    private static String describeIngredientError(BRecipe.IngredientResult.Error error) {
        Lang lang = ConfigManager.getConfig(Lang.class);
        String message = lang.getEntry(error.error().getTranslationKey(), error.invalidPart());
        return message == null || message.isBlank()
            ? error.error() + " at '" + error.invalidPart() + "'"
            : ChatColor.stripColor(message);
    }

    private static String knownRecipes() {
        return BRecipe.getAllRecipes().stream()
            .map(BRecipe::getId)
            .filter(id -> id != null && !id.isBlank())
            .distinct()
            .sorted(Comparator.naturalOrder())
            .reduce((a, b) -> a + ", " + b)
            .orElse("(none)");
    }

    static String describe(@Nullable Object value) {
        if (value == null) {
            return "empty";
        }
        String type = switch (value) {
            case String ignored -> "text";
            case Boolean ignored -> "a true/false value";
            case Number ignored -> "a number";
            case List<?> ignored -> "a list";
            case Map<?, ?> ignored -> "a map";
            default -> value.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        };
        return type + " (" + value + ")";
    }

    private static final String EXAMPLE = """
          tests:
            - name: Wheatbeer brewed to its recipe
              recipe: wheatbeer
              expect:
                quality: 10
        """;

}
