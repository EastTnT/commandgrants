package com.example.commandgrants;

import java.util.function.Predicate;

import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionProviderCheck;
import net.minecraft.server.permissions.Permissions;

/**
 * Reads a vanilla command level back out of the requirement a command node carries.
 *
 * <p>Vanilla writes "this command needs level N" as
 * {@code .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))}, and that call does
 * <em>not</em> put {@code Commands.LEVEL_GAMEMASTERS} on the node. {@code Commands.hasPermission}
 * wraps the check in a {@code PermissionProviderCheck}, a record that adapts a
 * {@code PermissionCheck} into the {@code Predicate<CommandSourceStack>} Brigadier wants:
 *
 * <pre>{@code
 * public record PermissionProviderCheck<T extends PermissionSetSupplier>(PermissionCheck test)
 *         implements Predicate<T> {
 *     public boolean test(T source) { return this.test.check(source.permissions()); }
 * }
 * }</pre>
 *
 * <p>So the predicate sitting on the node is the wrapper, never the constant, and comparing the two by
 * identity silently fails for every command in the game. That failure is invisible in the worst way: a
 * command whose level cannot be read is treated as ungated, which turns every filter built on this into
 * a no-op rather than an error. Hence the unwrapping here, in one place, for every caller.
 *
 * <p>Recognising the vanilla constants rather than interpreting the check is deliberate. A requirement
 * this class cannot name is reported as unknown ({@code null}) and left exactly as vanilla built it —
 * the mod refines what the game enforces and never rewrites it.
 */
final class VanillaLevel {
    private VanillaLevel() {
    }

    /**
     * The level a node's requirement stands for.
     *
     * @param requirement a node's requirement, or {@code null} for none
     * @return the level vanilla demands, {@link PermissionLevel#ALL} when the requirement admits
     *         everyone, or {@code null} when it is not a vanilla level check at all
     */
    static PermissionLevel of(Predicate<?> requirement) {
        return requirement == null ? null : ofCheck(unwrap(requirement));
    }

    /** Strips the {@code PermissionProviderCheck} adapter, leaving the check it consults. */
    private static PermissionCheck unwrap(Object requirement) {
        if (requirement instanceof PermissionCheck check) {
            return check;
        }
        if (requirement instanceof PermissionProviderCheck<?> provider) {
            return provider.test();
        }
        return null;
    }

    private static PermissionLevel ofCheck(PermissionCheck check) {
        if (check == null) {
            return null;
        }
        // Commands.LEVEL_ALL is AlwaysPass.INSTANCE, so "no gate at all" and "the always-pass
        // constant" are the same object and cannot be told apart — nor do they need to be.
        if (check == Commands.LEVEL_ALL) {
            return PermissionLevel.ALL;
        }
        if (check instanceof PermissionCheck.Require(Permission permission)) {
            return levelOf(permission);
        }
        return null;
    }

    /**
     * The level a permission stands for, for the checks the game installs on commands.
     *
     * <p>{@code Permissions.COMMANDS_ENTITY_SELECTORS} is included because
     * {@code LevelBasedPermissionSet.hasPermission} grants it at {@code GAMEMASTERS} — the selector
     * permission is a gamemaster gate in all but name, and leaving it unrecognised would let a
     * selector-gated command through the filter.
     */
    private static PermissionLevel levelOf(Permission permission) {
        if (permission == Permissions.COMMANDS_MODERATOR) {
            return PermissionLevel.MODERATORS;
        }
        if (permission == Permissions.COMMANDS_GAMEMASTER) {
            return PermissionLevel.GAMEMASTERS;
        }
        if (permission == Permissions.COMMANDS_ADMIN) {
            return PermissionLevel.ADMINS;
        }
        if (permission == Permissions.COMMANDS_OWNER) {
            return PermissionLevel.OWNERS;
        }
        if (permission == Permissions.COMMANDS_ENTITY_SELECTORS) {
            return PermissionLevel.GAMEMASTERS;
        }
        return null;
    }
}
