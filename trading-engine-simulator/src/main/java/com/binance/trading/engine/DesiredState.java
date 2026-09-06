package com.binance.trading.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * The operator's last instruction to the generator, kept somewhere a restart
 * cannot erase.
 *
 * <p><b>Why this exists.</b> Incident #2 (July 2026): a MySQL restart sent
 * {@code SIGTERM} to this process, systemd restarted it successfully, and the
 * order generator came back {@code STOPPED} - because whether it should run
 * lived only in an in-memory {@code AtomicBoolean} that resets to {@code false}
 * on boot. REST, WebSocket and the scheduler all came back. The business
 * threads did not. Six days.
 *
 * <p>Reproduced on 2026-09-06 while deploying a routine change:
 * {@code RUNNING -> systemctl restart -> STOPPED}. The fix is for the process
 * to remember what it was asked to do.
 *
 * <p><b>What is recorded, and what deliberately is not.</b> This stores
 * <i>operator intent</i>: the API calls to {@code /engine/start} and
 * {@code /engine/stop}. It is never written from the engine's own lifecycle.
 * That distinction is the whole design: {@code Main}'s shutdown hook calls
 * {@link TradingEngine#stop()} on every clean shutdown, so hooking persistence
 * there would record {@code STOPPED} on each {@code SIGTERM} - and the restart
 * would faithfully restore the very state this class exists to prevent.
 *
 * <p><b>Opt-in.</b> With {@code ENGINE_STATE_FILE} unset there is no
 * persistence and no behavioural change; the hermetic test suites and the k6
 * runner rely on a freshly started engine being {@code STOPPED}. The systemd
 * unit sets the variable to a path under its {@code StateDirectory}.
 *
 * <p>Writes are atomic (temp file + move), so a crash mid-write leaves the
 * previous value rather than an empty file.
 */
public final class DesiredState {

    private static final String RUNNING = "RUNNING";
    private static final String STOPPED = "STOPPED";

    private final Path file;   // null = persistence disabled

    private DesiredState(Path file) {
        this.file = file;
    }

    /** Reads {@code ENGINE_STATE_FILE}; unset or blank means disabled. */
    public static DesiredState fromEnv() {
        String p = System.getenv("ENGINE_STATE_FILE");
        return (p == null || p.isBlank()) ? disabled() : at(Path.of(p.trim()));
    }

    public static DesiredState at(Path file) {
        return new DesiredState(file);
    }

    public static DesiredState disabled() {
        return new DesiredState(null);
    }

    public boolean isEnabled() {
        return file != null;
    }

    /**
     * The last recorded intent, or empty when persistence is disabled, the file
     * does not exist yet (first boot), or its content is unrecognised.
     */
    public Optional<Boolean> load() {
        if (file == null || !Files.exists(file)) return Optional.empty();
        try {
            String v = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (RUNNING.equals(v)) return Optional.of(true);
            if (STOPPED.equals(v)) return Optional.of(false);
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Records intent. A failure to persist is reported, not swallowed. */
    public void save(boolean running) throws IOException {
        if (file == null) return;
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, (running ? RUNNING : STOPPED) + "\n", StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public Path path() {
        return file;
    }
}
