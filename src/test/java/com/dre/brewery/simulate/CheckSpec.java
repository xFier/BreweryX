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

import com.dre.brewery.recipe.BCauldronRecipe;
import com.dre.brewery.recipe.BRecipe;
import org.bukkit.ChatColor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The {@code checks:} section: which rules to apply to which recipes and ferments.
 * <p>
 * Loaded in the same two passes as the rest of the spec — {@link #parse} is structural and happens
 * before BreweryX is up, {@link #resolve} turns names into recipes once they are loaded.
 */
record CheckSpec(List<String> blanketRules, List<Entry> overrides) {

    private static final Set<String> ROOT_KEYS = new LinkedHashSet<>(List.of("all", "overrides"));
    private static final Set<String> ENTRY_KEYS =
        new LinkedHashSet<>(List.of("recipe", "ferment", "rules", "except", "expect"));

    /** Nothing selected: no blanket sweep and no named subjects. */
    static final CheckSpec EMPTY = new CheckSpec(List.of(), List.of());

    /** What kind of thing an override names. */
    enum Kind {
        RECIPE("recipe"),
        FERMENT("ferment");

        private final String key;

        Kind(String key) {
            this.key = key;
        }
    }

    /**
     * One named subject's departure from the blanket selection.
     *
     * @param kind   whether {@code name} is a recipe or a ferment
     * @param name   the recipe or ferment id, as written
     * @param rules  what to run instead of the blanket selection, or null to keep it. An empty
     *               list opts the subject out entirely.
     * @param except rules to drop from whatever was selected, presets included
     * @param expect per-rule tweaks, merged over the rule's own derived expectations
     */
    record Entry(Kind kind, String name, @Nullable List<String> rules, List<String> except,
                 Map<String, Map<String, Object>> expect) {
    }

    /** A subject, the rules to run against it, and any hand-written tweaks. */
    record Selection(RuleSubject subject, List<BrewRule> rules, Map<String, Map<String, Object>> expect) {
    }

    boolean isEmpty() {
        return blanketRules.isEmpty() && overrides.isEmpty();
    }

    // ---------------------------------------------------------------- parsing

    /**
     * Reads the {@code checks:} value, which is either a map with {@code all:} and
     * {@code overrides:}, or a plain list of overrides.
     *
     * @param value    the value YAML gave us
     * @param problems collects anything wrong
     * @return the parsed section, empty if it could not be understood
     */
    static CheckSpec parse(@Nullable Object value, List<String> problems) {
        if (value == null) {
            return EMPTY;
        }

        List<String> blanket = List.of();
        Object overridesValue;

        if (value instanceof Map<?, ?> map) {
            for (Object key : map.keySet()) {
                String name = String.valueOf(key);
                if (!ROOT_KEYS.contains(name)) {
                    problems.add("in `checks`, " + Suggestions.unknown("key", name, ROOT_KEYS));
                }
            }
            if (map.containsKey("all")) {
                blanket = readRules(map.get("all"), "checks.all", problems);
            }
            overridesValue = map.get("overrides");
        } else if (value instanceof List<?>) {
            overridesValue = value;
        } else {
            problems.add("`checks` should be a list of recipes to check, or a map with `all:` and "
                + "`overrides:`, but is " + BrewSpec.describe(value));
            return EMPTY;
        }

        List<Entry> overrides = new ArrayList<>();
        if (overridesValue != null) {
            if (!(overridesValue instanceof List<?> list)) {
                problems.add("`checks.overrides` should be a list of recipes, but is "
                    + BrewSpec.describe(overridesValue));
            } else {
                for (int i = 0; i < list.size(); i++) {
                    Entry entry = parseEntry(list.get(i), "checks[" + i + "]", problems);
                    if (entry != null) {
                        overrides.add(entry);
                    }
                }
            }
        }

        return new CheckSpec(blanket, List.copyOf(overrides));
    }

    private static @Nullable Entry parseEntry(@Nullable Object value, String where, List<String> problems) {
        if (!(value instanceof Map<?, ?> map)) {
            problems.add(where + " should name a `recipe:` or a `ferment:`, but is " + BrewSpec.describe(value));
            return null;
        }
        int problemsBefore = problems.size();

        for (Object key : map.keySet()) {
            String name = String.valueOf(key);
            if (!ENTRY_KEYS.contains(name)) {
                problems.add(where + ": " + Suggestions.unknown("key", name, ENTRY_KEYS));
            }
        }

        Kind kind = null;
        String subject = null;
        for (Kind candidate : Kind.values()) {
            Object named = map.get(candidate.key);
            if (named == null) {
                continue;
            }
            if (kind != null) {
                problems.add(where + " names both a `recipe:` and a `ferment:`; it should name one.");
                return null;
            }
            if (!(named instanceof String text)) {
                problems.add(where + ": `" + candidate.key + "` should be a name, but is "
                    + BrewSpec.describe(named));
                return null;
            }
            kind = candidate;
            subject = text;
        }
        if (kind == null) {
            problems.add(where + " names neither a `recipe:` nor a `ferment:`, so there is nothing to check.");
            return null;
        }

        String label = where + " \"" + subject + "\"";
        List<String> rules = map.containsKey("rules")
            ? readRules(map.get("rules"), label + " `rules`", problems)
            : null;
        List<String> except = map.containsKey("except")
            ? readRules(map.get("except"), label + " `except`", problems)
            : List.of();
        Map<String, Map<String, Object>> expect = readExpect(map.get("expect"), label, problems);

        if (problems.size() != problemsBefore) {
            return null;
        }
        return new Entry(kind, subject, rules, except, expect);
    }

    /**
     * Reads a rule selection, which may be a single preset name or a list of rule and preset names.
     */
    private static List<String> readRules(@Nullable Object value, String where, List<String> problems) {
        List<?> raw;
        if (value instanceof String single) {
            raw = List.of(single);
        } else if (value instanceof List<?> list) {
            raw = list;
        } else {
            problems.add(where + " should be a rule or preset name, or a list of them, but is "
                + BrewSpec.describe(value) + ". Presets: " + String.join(", ", BrewRules.presetNames()));
            return List.of();
        }

        List<String> names = new ArrayList<>();
        for (Object element : raw) {
            if (!(element instanceof String name)) {
                problems.add(where + ": every entry should be a rule name, but one is "
                    + BrewSpec.describe(element));
                continue;
            }
            if (!BrewRules.selectableNames().contains(name)) {
                problems.add(where + ": " + Suggestions.unknown("rule", name, BrewRules.selectableNames()));
                continue;
            }
            names.add(name);
        }
        return List.copyOf(names);
    }

    private static Map<String, Map<String, Object>> readExpect(@Nullable Object value, String where,
                                                               List<String> problems) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            problems.add(where + ": `expect` should map a rule name to the expectations to override "
                + "for it, but is " + BrewSpec.describe(value));
            return Map.of();
        }

        Map<String, Map<String, Object>> byRule = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String rule = String.valueOf(entry.getKey());
            if (BrewRules.rule(rule) == null) {
                problems.add(where + ": in `expect`, " + Suggestions.unknown("rule", rule, BrewRules.ruleNames()));
                continue;
            }
            if (!(entry.getValue() instanceof Map<?, ?> expectations)) {
                problems.add(where + ": `expect." + rule + "` should be a map of expected values, but is "
                    + BrewSpec.describe(entry.getValue()));
                continue;
            }
            byRule.put(rule, BrewExpectation.validate(expectations, where + " `expect." + rule + "`", problems));
        }
        return Map.copyOf(byRule);
    }

    // ---------------------------------------------------------------- resolution

    /**
     * Works out which subjects to check and with what, now that the recipes are loaded.
     *
     * @return one selection per subject, recipes first and then ferments, each in config order
     * @throws SpecException if an override names something that does not exist
     */
    List<Selection> resolve() {
        List<String> problems = new ArrayList<>();
        Map<Kind, Map<String, Entry>> byName = indexOverrides(problems);

        List<Selection> selections = new ArrayList<>();
        for (BRecipe recipe : BRecipe.getAllRecipes()) {
            RuleSubject subject = new RuleSubject.Recipe(recipe);
            select(subject, byName.get(Kind.RECIPE), selections);
        }
        for (BCauldronRecipe ferment : BCauldronRecipe.getAllRecipes()) {
            RuleSubject subject = new RuleSubject.Ferment(ferment);
            select(subject, byName.get(Kind.FERMENT), selections);
        }

        if (!problems.isEmpty()) {
            throw new SpecException("Could not work out what `checks:` should run:", problems);
        }
        return List.copyOf(selections);
    }

    private void select(RuleSubject subject, Map<String, Entry> overridesByName, List<Selection> into) {
        Entry entry = match(subject, overridesByName);
        List<String> selected = entry != null && entry.rules() != null ? entry.rules() : blanketRules;
        if (selected.isEmpty()) {
            return;
        }

        // Expanded the same way as `rules:`, so a preset drops everything in it. Matching the raw
        // names instead would quietly exclude nothing: preset names and rule names are disjoint, so
        // `except: [boundaries]` would validate, run every rule it named, and say nothing about it.
        Set<String> except = entry == null ? Set.of()
            : BrewRules.expand(entry.except()).stream().map(BrewRule::name).collect(Collectors.toSet());
        List<BrewRule> rules = BrewRules.expand(selected).stream()
            .filter(rule -> rule.handles(subject))
            .filter(rule -> !except.contains(rule.name()))
            .toList();
        if (rules.isEmpty()) {
            return;
        }
        into.add(new Selection(subject, rules, entry == null ? Map.of() : entry.expect()));
    }

    /**
     * Overrides are matched on the subject's id or its display name, the same two handles the rest
     * of the spec accepts.
     */
    private static @Nullable Entry match(RuleSubject subject, @Nullable Map<String, Entry> overridesByName) {
        if (overridesByName == null) {
            return null;
        }
        for (String name : names(subject)) {
            Entry entry = overridesByName.get(name.toLowerCase(Locale.ROOT));
            if (entry != null) {
                return entry;
            }
        }
        return null;
    }

    private static Set<String> names(RuleSubject subject) {
        Set<String> names = new LinkedHashSet<>();
        names.add(subject.id());
        switch (subject) {
            case RuleSubject.Recipe recipe -> names.add(strip(recipe.recipe().getRecipeName()));
            case RuleSubject.Ferment ferment -> names.add(strip(ferment.recipe().getName()));
        }
        names.remove("");
        return names;
    }

    private static String strip(@Nullable String text) {
        String stripped = text == null ? null : ChatColor.stripColor(text);
        return stripped == null ? "" : stripped;
    }

    /**
     * Indexes the overrides by lower-cased name, reporting any that name nothing that exists — a
     * silently ignored override would otherwise look like a rule that mysteriously never ran.
     */
    private Map<Kind, Map<String, Entry>> indexOverrides(List<String> problems) {
        Map<Kind, Map<String, Entry>> byKind = new LinkedHashMap<>();
        for (Kind kind : Kind.values()) {
            byKind.put(kind, new LinkedHashMap<>());
        }

        for (Entry entry : overrides) {
            Set<String> known = knownNames(entry.kind());
            boolean exists = known.stream().anyMatch(name -> name.equalsIgnoreCase(entry.name()));
            if (!exists) {
                problems.add("no " + entry.kind().key + " called '" + entry.name() + "'. "
                    + "Loaded from " + HeadlessBrewery.configSource() + ": " + join(known));
                continue;
            }
            byKind.get(entry.kind()).put(entry.name().toLowerCase(Locale.ROOT), entry);
        }
        return byKind;
    }

    private static Set<String> knownNames(Kind kind) {
        Set<String> names = new LinkedHashSet<>();
        if (kind == Kind.RECIPE) {
            for (BRecipe recipe : BRecipe.getAllRecipes()) {
                names.addAll(names(new RuleSubject.Recipe(recipe)));
            }
        } else {
            for (BCauldronRecipe ferment : BCauldronRecipe.getAllRecipes()) {
                names.addAll(names(new RuleSubject.Ferment(ferment)));
            }
        }
        return names;
    }

    private static String join(Set<String> names) {
        return names.isEmpty() ? "(none)" : names.stream().sorted(Comparator.naturalOrder())
            .reduce((a, b) -> a + ", " + b).orElse("(none)");
    }

}
