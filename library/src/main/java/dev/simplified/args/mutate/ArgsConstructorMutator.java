package dev.simplified.args.mutate;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;
import dev.simplified.args.apt.ArgsConfig;
import dev.simplified.args.apt.ArgsField;
import dev.simplified.args.apt.ArgsFieldSelector;
import dev.simplified.args.apt.ArgsMode;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.GeneratedAnnotations;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.JavacTypeFactory;
import dev.simplified.shared.javac.NullnessAnnotations;

import javax.annotation.processing.Messager;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Synthesises the constructors {@code @AllArgsConstructor},
 * {@code @RequiredArgsConstructor} and {@code @NoArgsConstructor} describe.
 *
 * <p>One mutator serves all three. They are one field-selection policy with
 * three settings, exactly as Lombok models them, and splitting them would be
 * three copies of the same emitter that drift.
 *
 * <p>Three rules are load bearing:
 *
 * <ul>
 *   <li><b>These annotations add a constructor, they do not back off.</b>
 *       {@code @ClassBuilder} declines to synthesise when the author wrote a
 *       constructor; copying that guard here would silently do nothing on every
 *       constant-carrying enum, which declares one by necessity.</li>
 *   <li><b>{@code transient} fields are parameters.</b> The builder's field
 *       collection drops them, having no use for a field it must not persist,
 *       and reusing that view here would quietly shorten the constructor of
 *       every type a reflective framework populates.</li>
 *   <li><b>An {@code enum} constructor is forced {@code private}.</b> The
 *       language permits nothing else, and {@code access} is written there
 *       often enough that rejecting it would be the wrong reading.</li>
 * </ul>
 *
 * <p>{@code @BuilderArgsConstructor} is resolved here but emitted by the
 * builder pass, which alone knows how to shape a parameter for a field the
 * builder holds a default for.
 */
public final class ArgsConstructorMutator {

    private final JavacBridge bridge;
    private final Messager messager;
    private final TreeMaker make;
    private final Names names;
    private final JavacTypeFactory types;

    public ArgsConstructorMutator(JavacBridge bridge, Messager messager) {
        this.bridge = bridge;
        this.messager = messager;
        this.make = bridge.treeMaker();
        this.names = bridge.names();
        this.types = new JavacTypeFactory(make, names);
    }

    /**
     * Emits every constructor the target's annotations call for.
     *
     * @param targetElement the annotated type
     * @param hasClassBuilder whether the target also carries {@code @ClassBuilder}
     * @param builderExclude the names {@code @ClassBuilder(exclude)} removes,
     *        empty when there is no builder
     * @return {@code true} when mutation completed; {@code false} when the
     *         element has no resolvable source tree
     */
    public boolean mutate(TypeElement targetElement, boolean hasClassBuilder,
                          Set<String> builderExclude) {
        java.util.List<ArgsConfig> configs = ArgsConfig.written(targetElement);
        if (configs.isEmpty()) return true;
        if (!isLegalTarget(targetElement, configs)) return true;

        JCClassDecl target = bridge.treeOf(targetElement);
        if (target == null) return false;
        make.at(target.pos);

        boolean isEnum = targetElement.getKind() == ElementKind.ENUM;
        java.util.List<ArgsField> candidates =
            ArgsFieldSelector.candidates(target, targetElement);
        ArgsConstructorFactory factory = new ArgsConstructorFactory(make, names);

        // Reported rather than dropped quietly: the field is missing from a
        // signature the author has every reason to read as "all args". On a
        // @ClassBuilder target that holds the field, the omission is not merely
        // incomplete but fatal, so it is reported as an error naming both
        // annotations rather than left to javac - see reportLazyOmission.
        java.util.List<ArgsMode> emitting = emittingModes(configs);
        for (ArgsField lazy : ArgsFieldSelector.lazyFields(candidates)) {
            reportLazyOmission(lazy, emitting, hasClassBuilder, builderExclude);
        }

        // Keyed on the parameter type list so two annotations that resolve to
        // the same signature are reported here, naming both, rather than
        // reaching javac as a duplicate-constructor error on a line the author
        // cannot see.
        Map<String, ArgsMode> emitted = new LinkedHashMap<>();

        for (ArgsConfig config : configs) {
            if (!config.access().emits()) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    config.mode().annotationName() + "(access = NONE) generates nothing - "
                        + "delete the annotation instead", targetElement);
                continue;
            }
            if (config.mode() == ArgsMode.BUILDER) {
                if (!hasClassBuilder) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                        "@BuilderArgsConstructor names the field set of a generated builder - "
                            + targetElement.getSimpleName() + " carries no @ClassBuilder. Write "
                            + "@AllArgsConstructor for every field instead",
                        targetElement);
                }
                // Otherwise the builder pass emits it: only that pass can shape
                // a parameter for a field it holds a default for.
                continue;
            }

            java.util.List<ArgsField> selected =
                ArgsFieldSelector.select(config.mode(), candidates, builderExclude);
            java.util.List<ArgsField> forced = config.mode() == ArgsMode.NONE
                ? ArgsFieldSelector.unassignedFinals(candidates)
                : java.util.List.of();

            if (config.mode() == ArgsMode.NONE && !checkNoArgs(targetElement, config, forced)) continue;

            JCMethodDecl ctor = build(factory, config, isEnum, selected, forced);
            String signature = joinTypes(ctor.params);
            ArgsMode clash = emitted.putIfAbsent(signature, config.mode());
            if (clash != null) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    clash.annotationName() + " and " + config.mode().annotationName()
                        + " both generate " + targetElement.getSimpleName() + "("
                        + signature + ") - only one of them can",
                    targetElement);
                continue;
            }
            bridge.compat().appendDef(target, ctor);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // The @Lazy omission
    // ------------------------------------------------------------------

    /**
     * Says what happens to a {@code @Lazy} field, as a note or as an error.
     *
     * <p>Without {@code @ClassBuilder} the omission is only an omission. The
     * field then has to carry an initializer, so it is a {@code final} field
     * that is already definitely assigned and a constructor skipping it is
     * correct.
     *
     * <p>With one, and when the builder holds the field, the omission cannot
     * compile. {@code @Lazy} makes the field {@code final}; the builder strips
     * the declared initializer so its own constructor can hand the field a
     * {@code Supplier}, which is the only shape that can assign it; and any
     * constructor emitted here omits the field by construction. What reaches the
     * author is javac's definite-assignment failure on the class-declaration
     * brace, naming neither annotation. Synthesising an assignment instead is
     * not an option - an unsupplied {@code @Lazy} field has to fail at
     * {@code build()}, so a fabricated default would trade a diagnostic for a
     * wrong value.
     *
     * @param lazy the {@code @Lazy} candidate
     * @param emitting the modes about to put a constructor on the target
     * @param hasClassBuilder whether the target also carries {@code @ClassBuilder}
     * @param builderExclude the names {@code @ClassBuilder(exclude)} removes
     */
    private void reportLazyOmission(ArgsField lazy, java.util.List<ArgsMode> emitting,
                                    boolean hasClassBuilder, Set<String> builderExclude) {
        if (hasClassBuilder && !emitting.isEmpty()
            && ArgsFieldSelector.builderManages(lazy, builderExclude)) {
            String written = emitting.stream().map(ArgsMode::annotationName).distinct()
                .collect(Collectors.joining(" and "));
            messager.printMessage(Diagnostic.Kind.ERROR,
                "'" + lazy.name() + "' is @Lazy and the generated builder owns it - only the "
                    + "builder's own constructor can assign it, since @Lazy makes the field final "
                    + "and the builder strips its initializer to pass a Supplier instead. "
                    + written + " emits a second constructor that omits the field, leaving a blank "
                    + "final javac rejects as unassigned. Write @BuilderArgsConstructor instead, or "
                    + "take '" + lazy.name() + "' out of the builder with @BuilderIgnore and give "
                    + "it an initializer",
                lazy.element());
            return;
        }
        messager.printMessage(Diagnostic.Kind.NOTE,
            "'" + lazy.name() + "' is @Lazy, so it takes no constructor parameter - the field "
                + "holds a Lazy wrapper it initialises itself",
            lazy.element());
    }

    /**
     * The modes that are about to put a constructor on the target.
     *
     * <p>{@link ArgsMode#BUILDER} is absent because the builder pass emits it,
     * and a suppressed {@code access} because it is reported as an error and
     * emits nothing - which is what makes {@code @BuilderArgsConstructor} the
     * one member of the family that never triggers the {@code @Lazy} error.
     *
     * @param configs the target's resolved constructor annotations
     * @return the emitting modes, in resolution order
     */
    private static java.util.List<ArgsMode> emittingModes(java.util.List<ArgsConfig> configs) {
        java.util.List<ArgsMode> out = new java.util.ArrayList<>(configs.size());
        for (ArgsConfig config : configs) {
            if (config.mode() == ArgsMode.BUILDER) continue;
            if (!config.access().emits()) continue;
            out.add(config.mode());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Eligibility
    // ------------------------------------------------------------------

    /**
     * Rejects target shapes whose constructor is not the annotation's to write.
     *
     * <p>A record's canonical constructor is its declared contract and an
     * interface has no constructor at all. Records carry no application sites
     * anywhere in this workspace, so rejecting rather than guessing what
     * "all args" means beside a canonical constructor costs nothing and commits
     * to nothing.
     */
    private boolean isLegalTarget(TypeElement targetElement, java.util.List<ArgsConfig> configs) {
        ElementKind kind = targetElement.getKind();
        if (kind == ElementKind.CLASS || kind == ElementKind.ENUM) return true;
        String reason = switch (kind) {
            case RECORD -> "a record, whose canonical constructor is its contract";
            case INTERFACE -> "an interface, which has no constructor";
            case ANNOTATION_TYPE -> "an annotation type";
            default -> "not a class or enum";
        };
        for (ArgsConfig config : configs) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                config.mode().annotationName() + " is only supported on classes and enums - "
                    + targetElement.getSimpleName() + " is " + reason,
                targetElement);
        }
        return false;
    }

    /**
     * Checks the two things only {@code @NoArgsConstructor} can get wrong: a
     * {@code final} field it would leave unassigned, and a {@code force} that
     * has nothing to fill.
     */
    private boolean checkNoArgs(TypeElement targetElement, ArgsConfig config,
                                java.util.List<ArgsField> forced) {
        if (forced.isEmpty()) {
            if (config.force()) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "@NoArgsConstructor(force = true) has nothing to assign - "
                        + targetElement.getSimpleName() + " declares no final field without an "
                        + "initializer", targetElement);
            }
            return true;
        }
        if (config.force()) return true;
        messager.printMessage(Diagnostic.Kind.ERROR,
            "@NoArgsConstructor would leave final "
                + (forced.size() == 1 ? "field " : "fields ")
                + forced.stream().map(f -> "'" + f.name() + "'").collect(Collectors.joining(", "))
                + " unassigned - give an initializer, or write force = true to accept the JVM "
                + "zero value", targetElement);
        return false;
    }

    // ------------------------------------------------------------------
    // Synthesis
    // ------------------------------------------------------------------

    private JCMethodDecl build(ArgsConstructorFactory factory, ArgsConfig config, boolean isEnum,
                               java.util.List<ArgsField> selected,
                               java.util.List<ArgsField> forced) {
        ListBuffer<JCVariableDecl> params = new ListBuffer<>();
        ListBuffer<JCStatement> body = new ListBuffer<>();
        for (ArgsField f : selected) {
            // The parameter's type is the field's own, so the field's nullness
            // describes it verbatim and travels. Nothing selected here is
            // retyped: a @Lazy field, the one shape whose parameter becomes a
            // Supplier<T> whose null is the "never set" sentinel, is never a
            // parameter of this constructor at all.
            params.append(make.VarDef(
                make.Modifiers(Flags.PARAMETER, NullnessAnnotations.copy(f.element(), make, types)),
                names.fromString(f.name()),
                types.parseType(f.typeDisplay()),
                null));
            body.append(assign(f.name(), make.Ident(names.fromString(f.name()))));
        }
        // force = true, and only there: the field's own nullness contract is
        // deliberately not consulted, since the point of the attribute is to
        // let a JSON or persistence layer in without giving up final.
        for (ArgsField f : forced) body.append(assign(f.name(), zeroValue(f)));

        GeneratedAnnotations generated =
            new GeneratedAnnotations(make, types, config.emitGenerated());
        return factory.mint(config.access(), isEnum, params.toList(), body.toList(), generated);
    }

    private JCStatement assign(String field, JCExpression value) {
        return make.Exec(make.Assign(
            make.Select(make.Ident(names._this), names.fromString(field)), value));
    }

    /**
     * The JVM zero value for the field's declared type. An {@code int} literal
     * assigns to every narrower and wider primitive as a constant expression,
     * so only {@code boolean} and reference types need their own spelling.
     */
    private JCExpression zeroValue(ArgsField field) {
        TypeKind kind = field.element().asType().getKind();
        if (kind == TypeKind.BOOLEAN) return make.Literal(TypeTag.BOOLEAN, 0);
        if (kind.isPrimitive()) return make.Literal(TypeTag.INT, 0);
        return make.Literal(TypeTag.BOT, null);
    }

    /**
     * Whether the target already carries a generated constructor of the given
     * parameter types.
     *
     * <p>Read by the builder pass so a written annotation that resolves to the
     * builder's own signature is emitted once rather than twice. Only
     * pipeline-generated constructors are considered - one the author wrote is
     * theirs to duplicate or not, and javac reports that.
     *
     * @param target the class declaration to scan
     * @param params the parameter declarations to match against
     * @return whether a generated constructor of that shape is already present
     */
    public static boolean hasGeneratedConstructor(JCClassDecl target, List<JCVariableDecl> params) {
        String wanted = joinTypes(params);
        for (var def : target.defs) {
            if (!(def instanceof JCMethodDecl m)) continue;
            if (!m.name.contentEquals("<init>")) continue;
            if (!AstMarkers.isGenerated(m)) continue;
            if (joinTypes(m.params).equals(wanted)) return true;
        }
        return false;
    }

    /** The parameter types, as declared, which is what a collision is keyed on. */
    private static String joinTypes(List<JCVariableDecl> params) {
        StringBuilder out = new StringBuilder();
        for (JCVariableDecl param : params) {
            if (out.length() > 0) out.append(", ");
            out.append(param.vartype.toString());
        }
        return out.toString();
    }

}
