package dev.nom2s.font;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies {@link FontSanitizer} to every font handed to a native font engine.
 */
public final class FontGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger("nom2s");
    private static final boolean VERBOSE = Boolean.getBoolean("nom2s.verbose");

    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean();
    private static final AtomicBoolean REJECTION_LOGGED = new AtomicBoolean();
    private static final AtomicLong FONTS = new AtomicLong();
    private static final AtomicLong BYTES = new AtomicLong();

    private FontGuard() {
    }

    public static void sanitize(ByteBuffer fontData) {
        if (fontData == null) {
            return;
        }

        int stripped;
        try {
            stripped = FontSanitizer.sanitize(fontData);
        } catch (RuntimeException failure) {
            reject(fontData, failure);
            return;
        }

        if (stripped < 0) {
            reject(fontData, null);
            return;
        }
        if (stripped == 0) {
            return;
        }

        long fontCount = FONTS.incrementAndGet();
        long byteCount = BYTES.addAndGet(stripped);
        announce();
        if (VERBOSE) {
            LOGGER.info("nom2s: neutralized font {} ({} bytes of hinting bytecode)",
                    fontCount, stripped);
        } else {
            LOGGER.debug("nom2s: neutralized {} bytes of hinting bytecode", stripped);
        }

        if (VERBOSE && fontCount % 100 == 0) {
            LOGGER.info("nom2s: {} fonts neutralized, {} bytes removed", fontCount, byteCount);
        }
    }

    private static void reject(ByteBuffer fontData, RuntimeException failure) {
        if (!FontSanitizer.invalidate(fontData)) {
            throw new IllegalStateException("nom2s: refusing a font that cannot be sanitized", failure);
        }
        if (REJECTION_LOGGED.compareAndSet(false, true)) {
            if (failure == null) {
                LOGGER.warn("nom2s: rejected malformed or unsupported font data");
            } else {
                LOGGER.warn("nom2s: rejected font data after a sanitizer failure", failure);
            }
        }
    }

    private static void announce() {
        if (ANNOUNCED.compareAndSet(false, true)) {
            LOGGER.info("nom2s: TrueType hinting bytecode guard active");
        }
    }
}
