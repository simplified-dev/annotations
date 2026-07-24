package dev.simplified.shared.apt;

import javax.lang.model.element.Element;
import javax.lang.model.type.TypeMirror;

/**
 * One member a generated whole-object member is built over, as
 * {@link MemberSelector} resolved it.
 *
 * <p>Deliberately not {@code FieldSpec}: that type is shaped by what a builder
 * needs to know about a field - collector kind, singular name, retained
 * initializer - and answers none of the questions asked here, while filtering
 * out the {@code transient} fields {@code @ToString} keeps and folding in the
 * builder's own exclusions, which are a statement about what a caller supplies
 * rather than about what the object is.
 *
 * @param name the member's own name, used to match {@code of} and {@code exclude}
 * @param label the name printed for this member, which a written override may change
 * @param type the member's declared type, and the type the emission row is chosen from
 * @param element the declaring element, for anchoring a diagnostic
 * @param method whether the read is a zero-arg call rather than a field access
 * @param mutable whether the member can change after construction
 * @param rank sort key, higher first, with declaration order inside one rank
 */
public record MemberSpec(
    String name,
    String label,
    TypeMirror type,
    Element element,
    boolean method,
    boolean mutable,
    int rank
) {
}
