package demo;

import dev.simplified.annotations.ResourcePath;

import java.io.InputStream;

/**
 * Shot 6 - @ResourcePath inspection with both a hit and a miss visible
 * side-by-side. Composition goals:
 * <ul>
 *   <li>{@link #LOGO} resolves cleanly (no marker) - shows the inspection
 *       does not false-positive on real assets.</li>
 *   <li>{@link #MISSING} is reddened - shows the resource-not-found
 *       diagnostic. Hover for the tooltip before screenshotting.</li>
 *   <li>{@link #brokenBase} also marks - the {@code base} attribute points
 *       to a non-existent directory, which the inspection reports against
 *       the annotation attribute itself.</li>
 * </ul>
 *
 * <p>Pair this with {@code demo/src/main/resources/icons/logo.svg} (a real
 * file) so {@link #LOGO} is genuinely a hit.
 */
public final class AssetLoader {

    @ResourcePath
    public static final String LOGO = "icons/logo.svg";

    @ResourcePath
    public static final String MISSING = "icons/does-not-exist.svg";

    private AssetLoader() {}

    /**
     * Forwards a {@code @ResourcePath} parameter into a resource-loading sink
     * without prepending the declared base. The caller-side inspection
     * ({@code ResourcePathUsageInspection}) flags this with a quick-fix that
     * inserts {@code "icons/" + }.
     */
    public static InputStream load(@ResourcePath(base = "icons") String name) {
        return AssetLoader.class.getResourceAsStream(name);
    }

    /**
     * The base directory does not exist - reported on the annotation attribute.
     */
    @ResourcePath(base = "no-such-folder")
    public static final String brokenBase = "anything.txt";
}
