package dev.simplified.shared.apt;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves the members a whole-object member is generated over, for both
 * {@code @EqualsAndHashCode} and {@code @ToString}.
 *
 * <p>One component, because the two annotations disagreeing about what a type
 * is made of is a defect rather than a feature. Everything they genuinely
 * differ on arrives through {@link MemberPolicy}.
 *
 * <p>A record is walked through its enclosed fields rather than
 * {@code getRecordComponents()}. The two are one-to-one and in the same order,
 * and the field is where an annotation targeting {@code FIELD} is reliably
 * visible after the compiler has propagated it off the component - which is
 * what the marker pair depends on.
 *
 * <p>Deliberately free of javac internals so the pass can resolve its members
 * from the element model, before any tree has been touched.
 */
public final class MemberSelector {

    private static final String LAZY_FQN = "dev.simplified.annotations.Lazy";

    private MemberSelector() {}

    /**
     * Resolves the selected members in emission order.
     *
     * @param target the annotated type
     * @param policy the annotation's own selection rules
     * @param lookup the mirror attribute reader
     * @param messager sink for a rejected marker or an unmatched name
     * @return the selected members, empty when the type declares no state
     */
    public static List<MemberSpec> select(TypeElement target, MemberPolicy policy,
                                          AnnotationLookup lookup, Messager messager) {
        List<MemberSpec> selected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> components = recordAccessorNames(target);

        for (Element enclosed : target.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD) {
                MemberSpec spec = fromField((VariableElement) enclosed, policy, lookup, messager);
                if (spec != null) selected.add(spec);
            } else if (enclosed.getKind() == ElementKind.METHOD) {
                ExecutableElement method = (ExecutableElement) enclosed;
                // A record component's annotations are propagated onto both the
                // backing field and the accessor whenever the target allows
                // both - which the include marker does. Reading the accessor as
                // a second member would compare and print the one component
                // twice, and on a double it collides with its own prelude local
                // and fails the build on a line the author did not write.
                if (method.getParameters().isEmpty()
                    && components.contains(method.getSimpleName().toString())) {
                    continue;
                }
                MemberSpec spec = fromMethod(method, policy, lookup, messager);
                if (spec != null) selected.add(spec);
            }
        }
        for (MemberSpec spec : selected) seen.add(spec.name());

        selected = narrow(target, selected, seen, policy, messager);
        if (policy.honourRank()) selected.sort((a, b) -> Integer.compare(b.rank(), a.rank()));
        return selected;
    }

    /** The canonical accessor names of a record's components, empty for anything else. */
    private static Set<String> recordAccessorNames(TypeElement target) {
        if (target.getKind() != ElementKind.RECORD) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (RecordComponentElement component : target.getRecordComponents()) {
            out.add(component.getSimpleName().toString());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Candidates
    // ------------------------------------------------------------------

    private static MemberSpec fromField(VariableElement field, MemberPolicy policy,
                                        AnnotationLookup lookup, Messager messager) {
        Set<Modifier> modifiers = field.getModifiers();
        if (modifiers.contains(Modifier.STATIC)) return null;

        String name = field.getSimpleName().toString();
        // A synthesised slot - an outer-instance link, a switch map, this
        // feature's own hash memo. None of them is state the author declared,
        // and the test is on a '$' anywhere rather than only in front, since
        // the compiler's own names put it in the middle.
        if (name.indexOf('$') >= 0) return null;

        boolean excluded = lookup.hasAnnotation(field, policy.excludeFqn());
        AnnotationMirror include = lookup.findMirror(field, policy.includeFqn());
        if (excluded && include != null) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "'" + name + "' carries both the include and exclude markers for "
                    + policy.label() + " - keep one",
                field);
            return null;
        }
        if (excluded) return null;

        boolean lazy = lookup.hasAnnotation(field, LAZY_FQN);
        if (lazy && include != null) {
            // By the time this pass runs the field's storage is a Lazy<T>
            // wrapper, so neither the slot nor a forced read means what the
            // marker asks for. The method form says the same thing and says it
            // where the forcing is visible.
            messager.printMessage(Diagnostic.Kind.ERROR,
                "'" + name + "' is @Lazy, so " + policy.label() + " cannot read it directly - "
                    + "declare a zero-arg method carrying the include marker if the memoized "
                    + "value belongs here",
                field);
            return null;
        }
        if (lazy) return null;
        if (modifiers.contains(Modifier.TRANSIENT) && !policy.keepTransient() && include == null) return null;

        return new MemberSpec(
            name,
            label(name, include, policy, lookup),
            field.asType(),
            field,
            false,
            !modifiers.contains(Modifier.FINAL),
            rank(include, policy, lookup)
        );
    }

    private static MemberSpec fromMethod(ExecutableElement method, MemberPolicy policy,
                                         AnnotationLookup lookup, Messager messager) {
        AnnotationMirror include = lookup.findMirror(method, policy.includeFqn());
        if (include == null) return null;

        String name = method.getSimpleName().toString();
        if (method.getModifiers().contains(Modifier.STATIC)) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "'" + name + "' is static, so it holds no per-instance value to contribute to "
                    + policy.label(),
                method);
            return null;
        }
        if (!method.getParameters().isEmpty()) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "'" + name + "' takes parameters, so " + policy.label()
                    + " has nothing to pass it - only a zero-arg method can be included",
                method);
            return null;
        }
        if (method.getReturnType().getKind() == TypeKind.VOID) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "'" + name + "' returns void, so it produces no value for " + policy.label(),
                method);
            return null;
        }

        return new MemberSpec(
            name,
            label(name, include, policy, lookup),
            method.getReturnType(),
            method,
            true,
            true,
            rank(include, policy, lookup)
        );
    }

    private static String label(String name, AnnotationMirror include, MemberPolicy policy,
                                AnnotationLookup lookup) {
        if (include == null || !policy.honourRank()) return name;
        String written = lookup.stringAttr(include, "name", "");
        return written.isEmpty() ? name : written;
    }

    private static int rank(AnnotationMirror include, MemberPolicy policy, AnnotationLookup lookup) {
        if (include == null || !policy.honourRank()) return 0;
        return lookup.intAttr(include, "rank", 0);
    }

    // ------------------------------------------------------------------
    // Narrowing
    // ------------------------------------------------------------------

    /**
     * Applies {@code of} or {@code exclude}, reporting a name that matches no
     * selected member.
     *
     * <p>That report is the check earning the attribute's keep: a rename leaves
     * the string behind, and the member it used to name silently rejoins or
     * leaves the relation with nothing failing.
     */
    private static List<MemberSpec> narrow(TypeElement target, List<MemberSpec> selected,
                                           Set<String> seen, MemberPolicy policy, Messager messager) {
        if (!policy.of().isEmpty() && !policy.exclude().isEmpty()) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                policy.label() + " sets both 'of' and 'exclude' - they are two spellings of one "
                    + "choice and cannot both apply",
                target);
            return selected;
        }
        if (!policy.of().isEmpty()) {
            reportUnmatched(target, policy.of(), seen, "of", policy, messager);
            List<MemberSpec> out = new ArrayList<>(policy.of().size());
            for (MemberSpec spec : selected) {
                if (policy.of().contains(spec.name())) out.add(spec);
            }
            return out;
        }
        if (!policy.exclude().isEmpty()) {
            reportUnmatched(target, policy.exclude(), seen, "exclude", policy, messager);
            List<MemberSpec> out = new ArrayList<>(selected.size());
            for (MemberSpec spec : selected) {
                if (!policy.exclude().contains(spec.name())) out.add(spec);
            }
            return out;
        }
        return selected;
    }

    private static void reportUnmatched(TypeElement target, Set<String> names, Set<String> seen,
                                        String attribute, MemberPolicy policy, Messager messager) {
        for (String name : names) {
            if (seen.contains(name)) continue;
            ExecutableElement candidate = includableMethod(target, name);
            if (candidate == null) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    policy.label() + "(" + attribute + ") names '" + name
                        + "', which is not a member this selection reaches",
                    target);
                continue;
            }
            messager.printMessage(Diagnostic.Kind.ERROR,
                policy.label() + "(" + attribute + ") names '" + name
                    + "', which is a method rather than a field - mark it @"
                    + simpleName(policy.includeFqn()) + " to make it a member",
                candidate);
        }
    }

    /**
     * The method a name would reach once it carried the include marker, or
     * {@code null} when nothing the target declares could ever answer to it.
     *
     * <p>Only the shapes {@link #fromMethod} accepts qualify, so the remedy the
     * message names is one that works. A method already carrying the marker is
     * in {@code seen} and never arrives here.
     */
    private static ExecutableElement includableMethod(TypeElement target, String name) {
        for (Element enclosed : target.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) continue;
            ExecutableElement method = (ExecutableElement) enclosed;
            if (!method.getSimpleName().contentEquals(name)) continue;
            if (method.getModifiers().contains(Modifier.STATIC)) continue;
            if (!method.getParameters().isEmpty()) continue;
            if (method.getReturnType().getKind() == TypeKind.VOID) continue;
            return method;
        }
        return null;
    }

    private static String simpleName(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }

}
