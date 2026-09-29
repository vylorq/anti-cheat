package com.vylorq.anticheat.core.perm;

/**
 * Role rules that apply on top of permission nodes (section 5). All of these are enforced on the server
 * when an action is received, never only when a menu opens.
 */
public final class PermissionPolicy {
    private PermissionPolicy() {
    }

    /** Base rule from roles only. */
    public static boolean allowed(Role role, Perm perm) {
        return role.atLeast(perm.minimum());
    }

    /** Only the owner edits the system-exempt list. */
    public static boolean canEditExempt(Role actor) {
        return actor == Role.OWNER;
    }

    /** Admins can't change or delete the owner's claims. */
    public static boolean canModifyClaim(Role actor, boolean claimCreatedByOwner) {
        if (actor == Role.OWNER) {
            return true;
        }
        return actor == Role.ADMIN && !claimCreatedByOwner;
    }

    /** Admins can't punish or remove other admins, or the owner. */
    public static boolean canActOn(Role actor, Role target) {
        if (actor == Role.OWNER) {
            return target != Role.OWNER;
        }
        if (actor == Role.ADMIN) {
            return target == Role.PLAYER;
        }
        return false;
    }

    public static boolean canRemoveAdmin(Role actor) {
        return actor == Role.OWNER;
    }

    public static boolean canSeePrivateInfo(Role actor, boolean adminsAllowed) {
        return actor == Role.OWNER || (actor == Role.ADMIN && adminsAllowed);
    }

    public static boolean canUseSettings(Role actor, boolean adminsAllowed) {
        return actor == Role.OWNER || (actor == Role.ADMIN && adminsAllowed);
    }
}
