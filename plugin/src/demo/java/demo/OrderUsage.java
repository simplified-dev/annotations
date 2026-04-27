package demo;

/**
 * Shot 3 - autocomplete popup over a synthesised builder. Capture procedure:
 * <ol>
 *   <li>Place caret immediately after the dot in {@code Order.builder().}</li>
 *   <li>Press Ctrl+Space to force the completion popup</li>
 *   <li>Crop so the popup, the source line, and ~3 lines of context are
 *       visible. Hide the project tree (Alt+1) for a cleaner shot.</li>
 * </ol>
 *
 * <p>The popup should list every setter shape the augment provider injects:
 * {@code addItem}, {@code clearItems}, {@code items(...)}, {@code putHeader},
 * {@code putHeaderIfAbsent}, {@code headers(...)}, {@code isPublished()},
 * {@code isPublished(boolean)}, {@code isDraft()}, {@code isDraft(boolean)},
 * {@code description(String)}, {@code description(String, Object...)},
 * {@code trackingNumber(String)}, {@code trackingNumber(Optional)},
 * {@code build()}.
 */
public final class OrderUsage {

    public static Order example() {
        return Order.builder()
                .addItem("widget")
                .addItem("gadget")
                .putHeader("X-Trace", "abc-123")
                .isDraft()
                .description("Order placed by %s at %s", "alice", "14:32")
                .build();
    }
}
