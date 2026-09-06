package com.binance.trading.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Incident #2 in miniature: intent must survive a "restart", which for this
 * class means a fresh instance reading the same file.
 */
class DesiredStateTest {

    @Test
    @DisplayName("First boot: no file, no opinion — the engine stays STOPPED as before")
    void absentFileMeansUnknown(@TempDir Path dir) {
        DesiredState s = DesiredState.at(dir.resolve("desired-state"));
        assertEquals(Optional.empty(), s.load());
    }

    @Test
    @DisplayName("RUNNING survives a restart: a new instance reading the same file sees it")
    void intentSurvivesRestart(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("desired-state");
        DesiredState.at(f).save(true);

        // "restart": a brand-new object, nothing shared in memory
        assertEquals(Optional.of(true), DesiredState.at(f).load(),
            "operator asked for RUNNING; a restart must restore it");

        DesiredState.at(f).save(false);
        assertEquals(Optional.of(false), DesiredState.at(f).load(),
            "an explicit STOP must also survive - do not auto-start what the operator stopped");
    }

    @Test
    @DisplayName("Disabled (no ENGINE_STATE_FILE): never reads, never writes")
    void disabledIsInert(@TempDir Path dir) throws Exception {
        DesiredState s = DesiredState.disabled();
        assertFalse(s.isEnabled());
        s.save(true);                                   // must not throw
        assertEquals(Optional.empty(), s.load());
        assertEquals(0, Files.list(dir).count(), "disabled mode must not create files");
    }

    @Test
    @DisplayName("Garbage in the file is treated as unknown, not as RUNNING")
    void unrecognisedContentIsUnknown(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("desired-state");
        Files.writeString(f, "yes please\n");
        assertEquals(Optional.empty(), DesiredState.at(f).load(),
            "fail closed: never start the generator on a value we did not write");
    }

    @Test
    @DisplayName("Write is atomic: no .tmp is left behind and the file has exactly one line")
    void writeIsAtomic(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("nested/dir/desired-state");   // parent dirs are created
        DesiredState.at(f).save(true);
        assertTrue(Files.exists(f));
        assertFalse(Files.exists(f.resolveSibling("desired-state.tmp")), "temp file must be moved, not copied");
        assertEquals("RUNNING\n", Files.readString(f));
    }
}
