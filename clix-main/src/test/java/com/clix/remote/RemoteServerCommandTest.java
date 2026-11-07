package com.clix.remote;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemoteServerCommandTest {
    @Test void parsesRemoteServerWithoutChangingHttpEntry() {
        assertTrue(RemoteServerCommand.matches(new String[]{"serve", "--remote", "--port", "9000"}));
        assertFalse(RemoteServerCommand.matches(new String[]{"serve", "--http"}));
        assertEquals(8080, RemoteServerCommand.port(new String[]{"serve", "--remote"}));
        assertEquals(9000, RemoteServerCommand.port(new String[]{"serve", "--remote", "--port", "9000"}));
        assertThrows(IllegalArgumentException.class, () -> RemoteServerCommand.port(new String[]{"serve", "--remote", "--port", "99999"}));
    }
}
