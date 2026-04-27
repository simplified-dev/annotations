package demo;

import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.Collector;
import dev.simplified.annotations.Formattable;
import dev.simplified.annotations.Negate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shot 2 - the rich-companion-annotation showcase. Composition goals:
 * <ul>
 *   <li>One field per setter shape so the autocomplete popup (caret after
 *       {@code Order.builder().}) shows the full menu in one screen</li>
 *   <li>{@link Collector} on both a list and a map so add/put/clear/putIfAbsent
 *       all appear</li>
 *   <li>{@link Negate}, {@link Formattable}, {@link Optional} dual setters all
 *       co-located on consecutive lines for visual density</li>
 * </ul>
 */
@ClassBuilder(generateFrom = false, generateMutate = false)
public final class Order {

    @Collector(singular = true, clearable = true)
    private final List<String> items;

    @Collector(singular = true, compute = true)
    private final Map<String, String> headers;

    @Negate("draft")
    private final boolean published;

    @Formattable
    private final String description;

    private final Optional<String> trackingNumber;

    private Order(
            List<String> items,
            Map<String, String> headers,
            boolean published,
            String description,
            Optional<String> trackingNumber
    ) {
        this.items = List.copyOf(items);
        this.headers = Map.copyOf(headers);
        this.published = published;
        this.description = description;
        this.trackingNumber = trackingNumber;
    }

}
