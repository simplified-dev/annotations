package dev.simplified.classbuilder.apt;

import dev.simplified.annotations.CallSuper;
import dev.simplified.annotations.EqualsAndHashCode;
import dev.simplified.equality.apt.EqualityConfig;
import dev.simplified.equality.apt.MemberWarnings;
import dev.simplified.shared.apt.AnnotationLookup;
import dev.simplified.shared.apt.MemberSpec;
import dev.simplified.tostring.apt.ToStringConfig;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The whole-object members {@code InterfaceImplEmitter} is about to write,
 * already narrowed and already formatted.
 *
 * <p>Resolved here rather than by letting a later round mutate the emitted
 * class. The Impl declares {@code equals}, {@code hashCode} and
 * {@code toString} itself, so an annotation copied onto it would land on a type
 * that already has all three - a duplicate member at best, and at worst an
 * annotation that compiles away having configured nothing.
 *
 * <p>Narrowing runs against accessor names throughout, which needs no
 * translation: an interface accessor's name is its {@link FieldSpec} name is
 * the field name on the Impl.
 *
 * @param compared the members equality reads, in declaration order
 * @param printed the members {@code toString} renders, in the order it renders them
 * @param includeFieldNames whether each printed value is prefixed with its label
 * @param open the bracket opening the rendered output
 * @param close the bracket closing it
 */
record ImplPlan(
    List<FieldSpec> compared,
    List<Printed> printed,
    boolean includeFieldNames,
    String open,
    String close
) {

    /**
     * One member {@code toString} renders, carrying the two things
     * {@code @ToStringInclude} can say about it.
     *
     * <p>A bare {@link FieldSpec} list cannot hold either: the label differs
     * from the field name whenever a rename was written, and the order differs
     * from declaration order whenever a rank was.
     *
     * @param field the accessor's field on the emitted class
     * @param label the name printed for this member, which a written override may change
     * @param rank sort key, higher first, with declaration order inside one rank
     */
    record Printed(FieldSpec field, String label, int rank) {}

    /** The include markers, each paired with the annotation a diagnostic names. */
    private static final String[][] INCLUDE_MARKERS = {
        {EqualityConfig.INCLUDE_FQN, EqualityConfig.LABEL},
        {ToStringConfig.INCLUDE_FQN, ToStringConfig.LABEL}
    };

    /**
     * The plan an interface carrying neither annotation gets - every accessor,
     * named, in square brackets.
     *
     * @param fields the accessors the builder models
     * @return the unconfigured plan
     */
    static ImplPlan defaults(List<FieldSpec> fields) {
        List<Printed> printed = new ArrayList<>(fields.size());
        for (FieldSpec field : fields) printed.add(new Printed(field, field.name, 0));
        return new ImplPlan(fields, printed, true, "[", "]");
    }

    /**
     * Reads whatever the interface carries, reporting every attribute that
     * cannot reach a generated Impl.
     *
     * <p>Both configurations are read unconditionally, because
     * {@code EqualityConfig.from} and {@code ToStringConfig.from} answer with
     * the defaults for an absent annotation - so the narrowing and formatting
     * below need no second spelling for the unannotated case, and the
     * inapplicable reports below cannot fire on one.
     *
     * <p>A marker is honoured whether or not its annotation was written. On
     * this path the three members are emitted for every interface, so an
     * accessor carrying {@code @ToStringExclude} alone has said the only thing
     * that marker can mean, and one carrying {@code @ToStringInclude(name)} has
     * said the only thing an include marker can mean here.
     *
     * @param target the annotated interface
     * @param implName the simple name of the class being emitted, for diagnostics
     * @param fields the accessors the builder models
     * @param lookup the mirror attribute reader
     * @param messager sink for diagnostics, anchored on the accessor where one
     *        is in hand and on {@code target} otherwise - an interface accessor
     *        produces no {@code VariableElement} to report an attribute against
     * @return the resolved plan
     */
    static ImplPlan resolve(TypeElement target, String implName, List<FieldSpec> fields,
                            AnnotationLookup lookup, Messager messager) {
        Map<String, ExecutableElement> accessors = accessors(target);
        // Ahead of the early return: a marker naming a member this path cannot
        // produce is written on a method the scan below never reaches, so the
        // unconfigured check would answer 'nothing written' and swallow it.
        reportUnreachableIncludes(target, implName, fields, lookup, messager);
        if (unconfigured(target, accessors, lookup)) return defaults(fields);

        EqualityConfig equality = EqualityConfig.from(target);
        reportInapplicable(target, implName, equality, messager);
        ToStringConfig toString = ToStringConfig.from(target);
        reportInapplicable(target, implName, toString, messager);

        List<FieldSpec> compared = select(target, fields, accessors, equality.of(), equality.exclude(),
            EqualityConfig.EXCLUDE_FQN, EqualityConfig.INCLUDE_FQN, EqualityConfig.LABEL,
            lookup, messager);
        for (FieldSpec field : compared) {
            ExecutableElement accessor = accessors.get(field.name);
            // The limits of generated equality, which the class path reports per
            // member and which an interface accessor runs into identically.
            MemberWarnings.report(
                new MemberSpec(field.name, field.name, field.type, accessor, true, false, 0),
                target, messager);
            if (accessor != null && lookup.hasAnnotation(accessor, EqualityConfig.INCLUDE_FQN)) {
                messager.printMessage(Diagnostic.Kind.NOTE,
                    "@EqualsInclude on '" + field.name + "' selects nothing new - every accessor "
                        + implName + " is built from is already compared",
                    accessor);
            }
        }

        List<FieldSpec> printable = select(target, fields, accessors, toString.of(), toString.exclude(),
            ToStringConfig.EXCLUDE_FQN, ToStringConfig.INCLUDE_FQN, ToStringConfig.LABEL,
            lookup, messager);
        return new ImplPlan(compared,
            printOrder(implName, printable, accessors, lookup, messager),
            toString.includeFieldNames(), toString.open(), toString.close());
    }

    /**
     * Whether nothing about the whole-object members was written at all -
     * neither annotation on the type, no marker on an accessor - which is the
     * case {@link #defaults} has to leave untouched.
     */
    private static boolean unconfigured(TypeElement target,
                                        Map<String, ExecutableElement> accessors,
                                        AnnotationLookup lookup) {
        if (lookup.hasAnnotation(target, EqualityConfig.FQN)) return false;
        if (lookup.hasAnnotation(target, ToStringConfig.FQN)) return false;
        for (ExecutableElement accessor : accessors.values()) {
            if (lookup.hasAnnotation(accessor, EqualityConfig.EXCLUDE_FQN)) return false;
            if (lookup.hasAnnotation(accessor, ToStringConfig.EXCLUDE_FQN)) return false;
            if (lookup.hasAnnotation(accessor, EqualityConfig.INCLUDE_FQN)) return false;
            if (lookup.hasAnnotation(accessor, ToStringConfig.INCLUDE_FQN)) return false;
        }
        return true;
    }

    /**
     * Reports a whole-object annotation on an interface whose {@code build()}
     * returns something other than the emitted implementation, where it has
     * nothing a caller will ever observe.
     *
     * <p>Two spellings reach this: {@code generateImpl = false}, which emits no
     * implementation at all, and a set {@code factoryMethod}, which still emits
     * one but never constructs it. Both leave the annotation configuring a class
     * nobody holds, so both have to say so.
     *
     * <p>A warning rather than an error: either spelling hands the author's own
     * factory the job of producing the instance, and that type may perfectly
     * well carry its own {@code @EqualsAndHashCode}. What it may not do is take
     * this one silently.
     *
     * @param target the annotated interface
     * @param cause the {@code @ClassBuilder} attribute that diverted the
     *        instance, phrased to follow "which"
     * @param lookup the mirror attribute reader
     * @param messager sink for the diagnostic
     */
    static void reportNoImpl(TypeElement target, String cause, AnnotationLookup lookup,
                             Messager messager) {
        for (String[] annotation : new String[][]{
            {EqualityConfig.FQN, EqualityConfig.LABEL},
            {ToStringConfig.FQN, ToStringConfig.LABEL}
        }) {
            if (!lookup.hasAnnotation(target, annotation[0])) continue;
            messager.printMessage(Diagnostic.Kind.WARNING,
                annotation[1] + " has no effect on " + target.getSimpleName() + ", which " + cause
                    + " - write it on the type factoryMethod returns, since that is the instance "
                    + "a caller holds",
                target);
        }
    }

    // ------------------------------------------------------------------
    // Narrowing
    // ------------------------------------------------------------------

    /**
     * The accessors one annotation keeps, after its marker and then its
     * {@code of} or {@code exclude}.
     */
    private static List<FieldSpec> select(TypeElement target, List<FieldSpec> fields,
                                          Map<String, ExecutableElement> accessors,
                                          Set<String> of, Set<String> exclude, String excludeFqn,
                                          String includeFqn, String label, AnnotationLookup lookup,
                                          Messager messager) {
        List<FieldSpec> candidates = new ArrayList<>(fields.size());
        Set<String> seen = new LinkedHashSet<>();
        for (FieldSpec field : fields) {
            ExecutableElement accessor = accessors.get(field.name);
            boolean excluded = accessor != null && lookup.hasAnnotation(accessor, excludeFqn);
            boolean included = accessor != null && lookup.hasAnnotation(accessor, includeFqn);
            // The same refusal the class path makes, in the same words. Picking
            // a winner would resolve a contradiction one way on one target kind
            // and refuse it on the other.
            if (excluded && included) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "'" + field.name + "' carries both the include and exclude markers for "
                        + label + " - keep one",
                    accessor);
                continue;
            }
            if (excluded) continue;
            candidates.add(field);
            seen.add(field.name);
        }

        if (!of.isEmpty() && !exclude.isEmpty()) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                label + " sets both 'of' and 'exclude' - they are two spellings of one choice "
                    + "and cannot both apply",
                target);
            return candidates;
        }
        if (!of.isEmpty()) {
            reportUnmatched(target, of, seen, "of", label, messager);
            return retain(candidates, of, true);
        }
        if (!exclude.isEmpty()) {
            reportUnmatched(target, exclude, seen, "exclude", label, messager);
            return retain(candidates, exclude, false);
        }
        return candidates;
    }

    private static List<FieldSpec> retain(List<FieldSpec> candidates, Set<String> names,
                                          boolean keep) {
        List<FieldSpec> out = new ArrayList<>(candidates.size());
        for (FieldSpec field : candidates) {
            if (names.contains(field.name) == keep) out.add(field);
        }
        return out;
    }

    /**
     * Applies {@code @ToStringInclude}'s two attributes to the selected members
     * and puts them in rendering order.
     *
     * <p>The sort is stable and by descending rank, which is what the class path
     * does - a printed order that depended on target kind would be the same
     * annotation meaning two things.
     *
     * <p>A marker written with neither attribute is reported. Every accessor is
     * already printed here, so it changed nothing, and an author who wrote it to
     * add a member back is owed that news.
     */
    private static List<Printed> printOrder(String implName, List<FieldSpec> selected,
                                            Map<String, ExecutableElement> accessors,
                                            AnnotationLookup lookup, Messager messager) {
        List<Printed> out = new ArrayList<>(selected.size());
        for (FieldSpec field : selected) {
            ExecutableElement accessor = accessors.get(field.name);
            AnnotationMirror include = accessor == null
                ? null : lookup.findMirror(accessor, ToStringConfig.INCLUDE_FQN);
            String written = include == null ? "" : lookup.stringAttr(include, "name", "");
            int rank = include == null ? 0 : lookup.intAttr(include, "rank", 0);
            if (include != null && written.isEmpty() && rank == 0) {
                messager.printMessage(Diagnostic.Kind.NOTE,
                    "@ToStringInclude on '" + field.name + "' selects nothing new - every accessor "
                        + implName + " is built from is already printed, and neither name nor rank "
                        + "was written",
                    accessor);
            }
            out.add(new Printed(field, written.isEmpty() ? field.name : written, rank));
        }
        out.sort((a, b) -> Integer.compare(b.rank(), a.rank()));
        return out;
    }

    /**
     * Reports an include marker on a method the emitted class holds no member
     * for - a {@code default} derived value, a {@code static} one, or a shape
     * the class path refuses outright.
     *
     * <p>An error rather than a note, and that is the whole distinction this
     * path draws. A marker on an accessor asks for a member that is already
     * there, so it costs the author nothing and is reported as a note; a marker
     * on anything else asks for a member the emission cannot produce, and there
     * is no reading of that request the generated class fulfils. Staying silent
     * would leave the same annotation adding a member on a class and doing
     * nothing at all here.
     */
    private static void reportUnreachableIncludes(TypeElement target, String implName,
                                                  List<FieldSpec> fields, AnnotationLookup lookup,
                                                  Messager messager) {
        Set<String> modelled = new LinkedHashSet<>();
        for (FieldSpec field : fields) modelled.add(field.name);
        for (Element enclosed : target.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) continue;
            ExecutableElement method = (ExecutableElement) enclosed;
            if (modelled.contains(method.getSimpleName().toString())) continue;
            for (String[] marker : INCLUDE_MARKERS) {
                if (!lookup.hasAnnotation(method, marker[0])) continue;
                unreachable(method, implName, marker[1], messager);
            }
        }
    }

    /** The reason one marked method contributes nothing, in the class path's words. */
    private static void unreachable(ExecutableElement method, String implName, String label,
                                    Messager messager) {
        String name = method.getSimpleName().toString();
        String because;
        if (method.getModifiers().contains(Modifier.STATIC)) {
            because = "is static, so it holds no per-instance value to contribute to " + label;
        } else if (!method.getParameters().isEmpty()) {
            because = "takes parameters, so " + label + " has nothing to pass it - only a zero-arg "
                + "method can be included";
        } else if (method.getReturnType().getKind() == TypeKind.VOID) {
            because = "returns void, so it produces no value for " + label;
        } else {
            because = "is not among the accessors " + implName + " is built from, so " + label
                + " cannot include it - that class holds a field only for an abstract zero-arg "
                + "accessor the builder models";
        }
        messager.printMessage(Diagnostic.Kind.ERROR, "'" + name + "' " + because, method);
    }

    /**
     * Reports a name that reaches no accessor. That report is what earns the
     * attribute its keep: a rename leaves the string behind, and the member it
     * used to name silently rejoins or leaves the relation with nothing failing.
     */
    private static void reportUnmatched(TypeElement target, Set<String> names, Set<String> seen,
                                        String attribute, String label, Messager messager) {
        for (String name : names) {
            if (seen.contains(name)) continue;
            messager.printMessage(Diagnostic.Kind.ERROR,
                label + "(" + attribute + ") names '" + name
                    + "', which is not a member this selection reaches",
                target);
        }
    }

    /**
     * The interface's zero-arg methods by name, which is where a marker written
     * against a generated field has to be read from - the field does not exist
     * until this emission produces it.
     */
    private static Map<String, ExecutableElement> accessors(TypeElement target) {
        Map<String, ExecutableElement> out = new LinkedHashMap<>();
        for (Element enclosed : target.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) continue;
            ExecutableElement method = (ExecutableElement) enclosed;
            if (!method.getParameters().isEmpty()) continue;
            out.putIfAbsent(method.getSimpleName().toString(), method);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Attributes an interface target cannot honour
    // ------------------------------------------------------------------

    private static void reportInapplicable(TypeElement target, String implName,
                                           EqualityConfig config, Messager messager) {
        if (config.identity() != EqualsAndHashCode.Identity.EXACT_CLASS) {
            inapplicable(target, EqualityConfig.LABEL, "identity",
                implName + " is final and extends Object, so all three relations pick out the "
                    + "same instances", messager);
        }
        if (config.callSuper() != CallSuper.AUTO) {
            inapplicable(target, EqualityConfig.LABEL, "callSuper",
                implName + " extends Object, whose equals is identity and would reject every "
                    + "distinct instance", messager);
        }
        if (config.useAccessors()) {
            inapplicable(target, EqualityConfig.LABEL, "useAccessors",
                "every member of " + implName + " is already read through the accessor it was "
                    + "derived from", messager);
        }
        if (config.cacheHashCode()) {
            inapplicable(target, EqualityConfig.LABEL, "cacheHashCode",
                implName + " holds no memo field to read", messager);
        }
    }

    private static void reportInapplicable(TypeElement target, String implName,
                                           ToStringConfig config, Messager messager) {
        if (config.callSuper() != CallSuper.AUTO) {
            inapplicable(target, ToStringConfig.LABEL, "callSuper",
                implName + " extends Object, whose toString prints an identity hash rather than "
                    + "any state", messager);
        }
        if (config.useAccessors()) {
            inapplicable(target, ToStringConfig.LABEL, "useAccessors",
                "every member of " + implName + " is already read through the accessor it was "
                    + "derived from", messager);
        }
    }

    private static void inapplicable(TypeElement target, String label, String attribute,
                                     String because, Messager messager) {
        messager.printMessage(Diagnostic.Kind.NOTE,
            label + "(" + attribute + ") does not apply to the interface "
                + target.getSimpleName() + " - " + because,
            target);
    }

}
