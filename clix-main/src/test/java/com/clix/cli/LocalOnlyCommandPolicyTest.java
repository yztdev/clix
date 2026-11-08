package com.clix.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocalOnlyCommandPolicyTest {
    @Test void disablesRemoteEntryPoints() {
        assertTrue(Main.isDeferredRemoteCommand(new String[]{"remote"}));
        assertTrue(Main.isDeferredRemoteCommand(new String[]{"login"}));
        assertTrue(Main.isDeferredRemoteCommand(new String[]{"serve", "--remote"}));
        assertFalse(Main.isDeferredRemoteCommand(new String[]{}));
        assertFalse(Main.isDeferredRemoteCommand(new String[]{"--help"}));
    }
}
