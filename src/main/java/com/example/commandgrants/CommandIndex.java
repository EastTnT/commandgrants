package com.example.commandgrants;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.server.permissions.PermissionLevel;

/**
 * The catalogue of root commands a host can hand out: every literal in the live tree, the level it
 * demands, and whether the host itself is allowed to offer it at all.
 *
 * <p>One command is one checkbox, which is why this is keyed by literal and not by level: {@code /tp}
 * and {@code /gamemode} are both {@code GAMEMASTERS}, and a host may well want the first without the
 * second. Granting is therefore always a decision about a <em>command</em>, never about a level.
 *
 * <p>One command can still wear two names. Vanilla registers {@code /tp} as a root literal that
 * redirects at {@code /teleport}, so the live tree has both and the catalogue lists both; they are
 * one command, and an entry therefore carries the other names under {@link Entry#group()}. Opening
 * either opens both, because a host who wants teleport does not want it only under its long name.
 *
 * <p>Immutable and side-effect free so the same instance can travel to clients as a sync payload.
 */
public record CommandIndex(Map<String, Entry> entries, Map<String, Set<String>> aliasGroups) {
    public static final CommandIndex EMPTY = new CommandIndex(Map.of(), Map.of());

    public CommandIndex(Map<String, Entry> entries, Map<String, Set<String>> aliasGroups) {
        this.entries = Map.copyOf(entries);
        this.aliasGroups = Map.copyOf(aliasGroups);
    }

    /**
     * @param literal   root command name, without the slash
     * @param required  the level the vanilla tree demands before the command is visible at all
     * @param offered   whether the host may offer it; free commands ({@code required == ALL}) are
     *                  never offered, because they are already available to everyone
     * @param available whether the host itself holds it; commands the host lacks cannot be granted
     * @param group     the names this command also answers to, comma separated ({@code "tp"} for
     *                  {@code teleport}); empty when it has only one name. A comma is safe as the
     *                  separator because Brigadier literals cannot contain one
     */
    public record Entry(String literal, PermissionLevel required, boolean offered, boolean available,
                        String group) {
        public boolean grantable() {
            return offered && available;
        }

        /** The other names this command answers to, or an empty list when it has just its own. */
        public List<String> aliases() {
            if (group == null || group.isBlank()) {
                return List.of();
            }
            List<String> names = new ArrayList<>(List.of(group.split(",")));
            names.sort(Comparator.naturalOrder());
            return names;
        }
    }

    public static CommandIndex build(CommandRequirements requirements,
                                     Predicate<PermissionLevel> hostHolds) {
        if (requirements == null) {
            return EMPTY;
        }
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (String literal : requirements.literals()) {
            PermissionLevel required = requirements.levelOf(literal);
            if (required == null) {
                continue;
            }
            boolean offered = GrantLevels.isGrantable(required);
            boolean available = hostHolds == null || hostHolds.test(required);
            Set<String> group = requirements.aliasGroup(literal);
            String aliases = "";
            if (group != null) {
                List<String> others = new ArrayList<>(group);
                others.remove(literal);
                others.sort(Comparator.naturalOrder());
                aliases = String.join(",", others);
            }
            entries.put(literal, new Entry(literal, required, offered, available, aliases));
        }
        return new CommandIndex(entries, requirements.aliasGroups());
    }

    /**
     * The names one command answers to, including its own; just its own when it stands alone.
     *
     * <p>This is the set every question about "is this command open?" has to ask, because opening one
     * name of a command that has two of them has to open the command.
     */
    public Set<String> group(String literal) {
        Set<String> group = this.aliasGroups.get(literal);
        return group == null ? Set.of(literal) : group;
    }

    public boolean isEmpty() {
        return this.entries.isEmpty();
    }

    public boolean contains(String literal) {
        return literal != null && this.entries.containsKey(literal);
    }

    public Entry entry(String literal) {
        return this.entries.get(literal);
    }

    public List<String> literals() {
        List<String> names = new ArrayList<>(this.entries.keySet());
        names.sort(Comparator.naturalOrder());
        return names;
    }

    /** Literals a host may actually offer: restricted, and held by the host. */
    public List<String> grantable() {
        List<String> names = new ArrayList<>();
        for (Entry entry : this.entries.values()) {
            if (entry.grantable()) {
                names.add(entry.literal());
            }
        }
        names.sort(Comparator.naturalOrder());
        return names;
    }

    /**
     * Expands a preset into concrete grants: every grantable command whose requirement the ceiling
     * covers. Commands the host lacks are skipped rather than granted, so a preset can never hand
     * out more than the host holds.
     */
    public Map<String, PermissionLevel> expand(PermissionLevel ceiling) {
        Map<String, PermissionLevel> granted = new LinkedHashMap<>();
        if (ceiling == null) {
            return granted;
        }
        for (Entry entry : this.entries.values()) {
            if (entry.grantable() && GrantLevels.covers(ceiling, entry.required())) {
                granted.put(entry.literal(), entry.required());
            }
        }
        return granted;
    }

    /** Keeps only literals this index knows about, so a stale grant cannot outlive its command. */
    public Map<String, PermissionLevel> retainKnown(Map<String, PermissionLevel> granted) {
        Map<String, PermissionLevel> kept = new LinkedHashMap<>();
        if (granted == null) {
            return kept;
        }
        granted.forEach((literal, level) -> {
            if (level != null && contains(literal)) {
                kept.put(literal, level);
            }
        });
        return kept;
    }
}
