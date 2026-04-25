package dev.simplified.annotations;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link String} field, method parameter, or method return value as a path to
 * a resource that must exist on the project's classpath.
 *
 * <p>Two companion inspections enforce the annotation statically - no runtime code is
 * generated and no bytecode is rewritten:
 * <ul>
 *   <li><b>Resource Path</b> - verifies that the value resolves to a file on disk and
 *       (if {@link #base} is set) that the base folder itself exists.</li>
 *   <li><b>Resource Path Base-Prefix Usage</b> - catches the common runtime mistake of
 *       forgetting that {@link #base} is a validation-time prefix only. Warns when the
 *       annotated parameter is passed raw into a resource-loading call (for example
 *       {@code getResourceAsStream} or {@code new File(...)}) and offers a quick-fix
 *       that prepends the base.</li>
 * </ul>
 *
 * <p>Values are resolved statically against the project's content source roots plus
 * any user-configured additional roots. Recognised value sources include direct
 * literals, binary concatenation, final/static/enum fields, local variables, return
 * values of other methods (recursively), and enum constructor argument bindings.
 *
 * <h2>Targets</h2>
 * <ul>
 *   <li>{@link ElementType#FIELD} - the field initialiser is evaluated and checked.</li>
 *   <li>{@link ElementType#PARAMETER} - every call site's matching argument is checked.
 *       Enum constructor parameters are handled via the enum constant's argument list.</li>
 *   <li>{@link ElementType#METHOD} - every call site of the method triggers a check on
 *       the method body's return values.</li>
 * </ul>
 *
 * <h2>Examples</h2>
 * <pre><code>
 * // Field with implicit root - validates config/settings.xml
 * &#64;ResourcePath
 * private String configFile = "config/settings.xml";
 *
 * // Field with base folder - validates images/icon.png
 * &#64;ResourcePath(base = "images")
 * private String iconFile = "icon.png";
 *
 * // Method parameter - every call site's argument is checked
 * void loadResource(&#64;ResourcePath(base = "templates") String fileName) {
 *     // Remember: at runtime 'fileName' is just the suffix; prepend base/ before
 *     // loading, or ResourcePathUsageInspection will flag it.
 *     InputStream in = getClass().getResourceAsStream("templates/" + fileName);
 * }
 *
 * // Method return - each return expression is evaluated and checked
 * &#64;ResourcePath(base = "config")
 * String defaultConfigName() { return "settings.xml"; }   // validates config/settings.xml
 *
 * // Enum constructor - bound through the constant's argument list
 * public enum Status {
 *     OK("logo.png");                                     // validates assets/logo.png
 *
 *     private final String iconPath;
 *     Status(&#64;ResourcePath(base = "assets") String iconPath) { this.iconPath = iconPath; }
 * }
 * </code></pre>
 *
 * @see #base
 */
@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD })
public @interface ResourcePath {

    /**
     * Optional base folder under which the resource path is resolved. Defaults to the
     * resources root when omitted.
     *
     * <p><b>Design note:</b> this is a validation-time prefix only. At runtime the annotated
     * string field or parameter still holds only the suffix (e.g. {@code "icon.png"}, not
     * {@code "assets/icon.png"}). The static inspection prepends {@code base} when checking
     * resource existence, but the method body that actually loads the resource is responsible
     * for prepending it at runtime - typically as {@code getClass().getResourceAsStream(base + "/" + name)}.
     * The {@code ResourcePathUsageInspection} flags call sites that forget to do this.
     *
     * @return the base folder path prefix
     */
    @NotNull String base() default "";

}
