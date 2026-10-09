package tn.vas.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProfileGuardVerifyTests {
    @Test
    void acceptsDevProOrTestAlone() {
        assertDoesNotThrow(() -> ProfileGuard.verify(new String[]{"dev"}));
        assertDoesNotThrow(() -> ProfileGuard.verify(new String[]{"pro", "json"}));
        assertDoesNotThrow(() -> ProfileGuard.verify(new String[]{"test"}));
    }

    @Test
    void rejectsNoneAndConflicts() {
        assertThrows(IllegalStateException.class, () -> ProfileGuard.verify(new String[]{}));
        assertThrows(IllegalStateException.class, () -> ProfileGuard.verify(new String[]{"json"}));
        assertTrue(assertThrows(IllegalStateException.class, () -> ProfileGuard.verify(new String[]{"dev", "pro"})).getMessage().contains("incompatibles"));
    }
}
