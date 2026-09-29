package com.vylorq.anticheat.core;

import com.vylorq.anticheat.core.perm.AdminPins;
import com.vylorq.anticheat.core.perm.PermissionPolicy;
import com.vylorq.anticheat.core.perm.Role;
import com.vylorq.anticheat.core.util.Clock;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PinsTest {
    @Test
    void pinFlow() {
        Clock.Manual clock = new Clock.Manual(0);
        AdminPins pins = new AdminPins(new HashMap<>(), clock, 3, 60_000);
        UUID a = UUID.randomUUID();
        assertEquals(AdminPins.Result.NO_PIN_SET, pins.login(a, "1234"));
        assertEquals(AdminPins.Result.INVALID_FORMAT, pins.setPin(a, "12"));
        assertEquals(AdminPins.Result.OK, pins.setPin(a, "4821"));
        assertNotEquals("4821", pins.data().get(a).hash, "stored hashed");
        pins.logout(a);
        assertFalse(pins.isLoggedIn(a));
        assertEquals(AdminPins.Result.WRONG, pins.login(a, "0000"));
        assertEquals(AdminPins.Result.WRONG, pins.login(a, "0000"));
        assertEquals(AdminPins.Result.LOCKED, pins.login(a, "0000"));
        assertEquals(AdminPins.Result.LOCKED, pins.login(a, "4821"), "locked even with correct pin");
        clock.advance(61_000);
        assertEquals(AdminPins.Result.OK, pins.login(a, "4821"));
        assertTrue(pins.isLoggedIn(a));
    }

    @Test
    void policies() {
        assertFalse(PermissionPolicy.canEditExempt(Role.ADMIN));
        assertTrue(PermissionPolicy.canEditExempt(Role.OWNER));
        assertFalse(PermissionPolicy.canModifyClaim(Role.ADMIN, true));
        assertTrue(PermissionPolicy.canModifyClaim(Role.ADMIN, false));
        assertFalse(PermissionPolicy.canActOn(Role.ADMIN, Role.ADMIN));
        assertFalse(PermissionPolicy.canActOn(Role.ADMIN, Role.OWNER));
        assertTrue(PermissionPolicy.canActOn(Role.OWNER, Role.ADMIN));
        assertFalse(PermissionPolicy.canSeePrivateInfo(Role.ADMIN, false));
        assertTrue(PermissionPolicy.canSeePrivateInfo(Role.ADMIN, true));
        assertFalse(PermissionPolicy.canRemoveAdmin(Role.ADMIN));
    }
}
