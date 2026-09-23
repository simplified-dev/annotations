package dev.simplified.classbuilder.mutate;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCTypeApply;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree;
import dev.simplified.classbuilder.apt.ChainRole;
import dev.simplified.classbuilder.apt.DeclaredBuildMethod;
import dev.simplified.classbuilder.apt.DeclaredBuilderFacts;
import dev.simplified.classbuilder.apt.DeclaredBuilderRejection;
import dev.simplified.classbuilder.apt.DeclaredBuilderShape;
import dev.simplified.classbuilder.apt.FieldSpec;
import dev.simplified.classbuilder.apt.RoleExpectation;
import org.jetbrains.annotations.Nullable;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Appends the generated builder's members into a {@code Builder} the target
 * already declares, so a builder needing one member the generator cannot
 * express does not have to be written out in full.
 *
 * <p>Runs whenever a class or record target declares a nested type by the
 * builder's name, so a single {@code apply(GsonContributor)} or a
 * {@code withField(String, String, boolean)} that constructs its own value
 * costs the author that one member and nothing more - every other setter, the
 * slot fields and {@code build()} still come from the generator.
 *
 * <h2>What wins</h2>
 * The author does, member for member. A generated member is appended only when
 * the declared builder does not already spell it:
 * <ul>
 *   <li>a <b>field</b> by name, so the generated setters assign the author's
 *       slot rather than a second one beside it;</li>
 *   <li>a <b>method</b> by name and parameter count. That is deliberately looser
 *       than the rule {@code BootstrapCollisions} applies on the target, and for
 *       the opposite reason: an unrelated {@code from(String)} there is a
 *       parser that happens to share a name, while a same-named,
 *       same-arity method <em>on the author's own builder</em> is the setter
 *       they wrote instead of the generated one, which is what merging is
 *       for;</li>
 *   <li>the builder's <b>constructor</b>, always. The declared class has one by
 *       the time this runs whether the author wrote it or not, javac having
 *       entered a default before the round began, and a second no-arg form
 *       beside either is a duplicate.</li>
 * </ul>
 *
 * <p>That last one is why {@code builderConstructorAccess} does not reach a
 * merged builder: javac's default was entered with the declared class's own
 * access and its symbol is what every later reference reads. An author wanting
 * {@code new Target.Builder()} closed off declares the constructor themselves,
 * which is the same thing they would do to any other class they wrote.
 *
 * <p>What is skipped is reported in one note rather than silently, because the
 * difference between "the author's version won" and "the generator never ran"
 * is invisible from the call site.
 */
final class DeclaredBuilderMerge {

    private final MutationContext ctx;
    private final Messager messager;

    DeclaredBuilderMerge(MutationContext ctx, Messager messager) {
        this.ctx = ctx;
        this.messager = messager;
    }

    /**
     * Merges the generated members into the declared builder.
     *
     * @param target the annotated type's declaration
     * @param targetElement the annotated type
     * @param declared the builder the target declares
     * @return whether the merge ran; {@code false} when the declared builder
     *     cannot host the generated members and an error was reported
     */
    boolean merge(JCClassDecl target, TypeElement targetElement, JCClassDecl declared) {
        if (!rejectUnusableShape(targetElement, declared)) return false;
        rejectMistypedSlots(targetElement, declared);

        Set<String> fields = declaredFieldNames(declared);
        Set<String> methods = declaredMethodKeys(declared);
        boolean authorOwnsConstruction = declaresConstructor(declared);

        List<String> skipped = new ArrayList<>();
        for (JCTree member : new NestedBuilderFactory(ctx).members()) {
            if (member instanceof JCVariableDecl field) {
                if (fields.contains(field.name.toString())) {
                    skipped.add(field.name.toString());
                    continue;
                }
            } else if (member instanceof JCMethodDecl method) {
                // A constructor is never appended here. The declared builder
                // always has one by now - the author's, or the default javac
                // entered before this round - and a second no-arg form beside
                // either is a duplicate. That default is also why
                // builderConstructorAccess does not reach a merged builder: it
                // was entered with the class's own access and its symbol is
                // already what every later reference reads.
                if (method.name.contentEquals("<init>")) {
                    skipped.add(declared.name + "(" + (authorOwnsConstruction ? "..)" : ")"));
                    continue;
                }
                if (methods.contains(key(method))) {
                    skipped.add(signature(method));
                    continue;
                }
            }
            ctx.bridge().compat().appendDef(declared, member);
        }

        if (!skipped.isEmpty()) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                "@ClassBuilder merged into the declared '" + declared.name + "'; "
                    + declared.name + " already spells " + String.join(", ", skipped)
                    + ", so the generated version was not added",
                targetElement);
        }
        return true;
    }

    /**
     * Reports the shapes a declared builder cannot take, returning whether the
     * merge may proceed.
     *
     * <p>The decision itself is {@link DeclaredBuilderShape#check}, which the
     * editor runs over the same facts read out of PSI, so a builder the editor
     * populates is a builder javac accepts. What is left here is filling the
     * facts from the tree and choosing the operands each rejection interpolates.
     */
    private boolean rejectUnusableShape(TypeElement targetElement, JCClassDecl declared) {
        ChainRole role = roleOf();
        RoleExpectation expectation = expectationFor(role);
        DeclaredBuilderFacts facts = factsOf(declared);
        DeclaredBuilderRejection rejection = DeclaredBuilderShape.check(role, facts, expectation);
        if (rejection == null) return true;
        messager.printMessage(Diagnostic.Kind.ERROR,
            DeclaredBuilderShape.describe(rejection, declared.name.toString(),
                ctx.targetSimpleName(), ctx.config().builderMethodName(), facts, expectation),
            targetElement);
        return false;
    }

    /**
     * Where the target this merge runs for sits in a chain.
     *
     * <p>Only the standalone branch reaches the merge today - the chain branch
     * returns ahead of the declared-builder check - so this answers the one role
     * that gets here, while the decision it feeds is written for all four.
     *
     * @return the target's role
     */
    private ChainRole roleOf() {
        return ChainRole.STANDALONE;
    }

    /**
     * What the role requires of the declared builder.
     *
     * @param role the target's position in a chain
     * @return the parameter names, supertype and build return type to measure against
     */
    private RoleExpectation expectationFor(ChainRole role) {
        List<String> targetParameters = new ArrayList<>();
        for (JCTypeParameter parameter : ctx.typeParams()) {
            targetParameters.add(parameter.name.toString());
        }
        List<String> selfNames = List.of(ctx.selfTypeName(), ctx.selfBuilderName());
        return new RoleExpectation(
            DeclaredBuilderShape.expectedTypeParameters(role, targetParameters, selfNames),
            DeclaredBuilderShape.expectedSuperType(role, null),
            DeclaredBuilderShape.expectedBuildReturnType(role, ctx.targetSimpleName(),
                ctx.selfTypeName()));
    }

    /**
     * Reads the declared builder as written, taking nothing from the element
     * model - the round is still building this tree.
     *
     * @param declared the builder the author wrote
     * @return the facts the shape decision measures
     */
    private DeclaredBuilderFacts factsOf(JCClassDecl declared) {
        List<String> parameterNames = new ArrayList<>();
        List<String> parameterBounds = new ArrayList<>();
        if (declared.typarams != null) {
            for (JCTypeParameter parameter : declared.typarams) {
                parameterNames.add(parameter.name.toString());
                parameterBounds.add(parameter.bounds == null || parameter.bounds.isEmpty()
                    ? null
                    : parameter.bounds.head.toString());
            }
        }
        String writtenSuper = declared.extending == null
            ? null
            : erasedName(declared.extending.toString());
        List<String> superArguments = new ArrayList<>();
        if (declared.extending instanceof JCTypeApply applied) {
            for (JCExpression argument : applied.arguments) superArguments.add(argument.toString());
        }
        return new DeclaredBuilderFacts((declared.mods.flags & Flags.STATIC) != 0,
            (declared.mods.flags & Flags.ABSTRACT) != 0,
            parameterNames, parameterBounds, writtenSuper, superArguments,
            declaredBuildMethod(declared));
    }

    /**
     * The no-argument build method the author wrote, by the configured name.
     *
     * @param declared the builder the author wrote
     * @return the method as written, or {@code null} when the class declares none
     */
    private @Nullable DeclaredBuildMethod declaredBuildMethod(JCClassDecl declared) {
        String buildName = ctx.config().buildMethodName();
        for (JCTree def : declared.defs) {
            if (!(def instanceof JCMethodDecl method)) continue;
            if (!method.name.contentEquals(buildName) || !method.params.isEmpty()) continue;
            String returnType = method.restype == null ? "" : erasedName(method.restype.toString());
            return new DeclaredBuildMethod(returnType,
                (method.mods.flags & Flags.ABSTRACT) != 0);
        }
        return null;
    }

    /**
     * Reports a declared slot field whose type is not the one the builder holds
     * that slot in, which the generated setter would otherwise fail to assign on
     * a line the author never wrote.
     *
     * <p>Compared against the <em>storage</em> type rather than the field's
     * declared type, because the two differ for three shapes and the setters
     * assign into the first. Comparing against the declared type gets a lazy
     * field wrong in both directions at once: it rejects the supplier spelling,
     * which is the only one the generated setters can assign, and accepts the
     * natural one, which then fails on a generated line - the exact outcome this
     * check exists to prevent.
     *
     * <p>Compared on the rendered type rather than through the element model,
     * which is what is available for a tree the round is still building. A false
     * negative here is only a missed diagnostic - javac still refuses the
     * assignment - so the comparison errs towards saying nothing.
     */
    private void rejectMistypedSlots(TypeElement targetElement, JCClassDecl declared) {
        for (JCTree def : declared.defs) {
            if (!(def instanceof JCVariableDecl field)) continue;
            String name = field.name.toString();
            for (FieldSpec slot : ctx.fields()) {
                if (!slot.name.equals(name)) continue;
                if (field.vartype == null) continue;
                String storage = slotStorageType(slot);
                if (erasedName(field.vartype.toString()).equals(erasedName(storage))) continue;
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "@ClassBuilder merged into '" + declared.name + "' finds '" + name
                        + "' declared as " + field.vartype + ", and the slot it stands for is "
                        + storage + " - the generated setter has nothing to assign it to"
                        + heldIndirectly(slot),
                    targetElement);
            }
        }
    }

    /**
     * The type the generated builder declares the slot as, which is not always
     * the type the field is declared with.
     *
     * <p>Three shapes, matching {@code FieldMutators} exactly: a collected field
     * whose default reads instance state gathers into a plain {@code java.util}
     * scratch container, a lazy field or one whose default is computed on the
     * instance is held as a supplier so the slot can carry "unset" without
     * ambiguity, and everything else is held as declared.
     *
     * @param slot the slot being merged
     * @return the storage type, rendered
     */
    private String slotStorageType(FieldSpec slot) {
        if (ctx.isCollectedInstanceDefault(slot)) return ctx.collectedSlotType(slot).toString();
        if (slot.lazy || ctx.isInstanceDefault(slot.name)) {
            return "java.util.function.Supplier<" + slot.typeDisplay + ">";
        }
        return slot.typeDisplay;
    }

    /**
     * Why a slot is held as something other than its declared type, for the two
     * shapes where an author would otherwise read the storage type as a mistake
     * in the generator rather than a property of their own field.
     *
     * @param slot the slot being merged
     * @return the trailing clause, empty where the slot is held as declared
     */
    private String heldIndirectly(FieldSpec slot) {
        // A collected slot with an instance-computed default is held as a plain
        // java.util container, not as a supplier - the storage type above has
        // already printed it as one, and the supplier clause would contradict
        // the sentence it is appended to.
        if (ctx.isCollectedInstanceDefault(slot)) return "";
        if (slot.lazy) {
            return ". A @Lazy field is held in the builder as a supplier of its declared type";
        }
        if (ctx.isInstanceDefault(slot.name)) {
            return ". A slot whose retained initializer reads instance state is held in the builder "
                + "as a supplier of its declared type";
        }
        return "";
    }

    /** Field names the declared builder already spells. */
    private static Set<String> declaredFieldNames(JCClassDecl declared) {
        Set<String> out = new HashSet<>();
        for (JCTree def : declared.defs) {
            if (def instanceof JCVariableDecl field) out.add(field.name.toString());
        }
        return out;
    }

    /** {@code name/arity} keys the declared builder already spells. */
    private static Set<String> declaredMethodKeys(JCClassDecl declared) {
        Set<String> out = new HashSet<>();
        for (JCTree def : declared.defs) {
            if (def instanceof JCMethodDecl method && !method.name.contentEquals("<init>")) {
                out.add(key(method));
            }
        }
        return out;
    }

    /**
     * Whether the author wrote the declared builder a constructor.
     *
     * <p>Asked through {@link AllArgsConstructorFactory#hasExplicitConstructor},
     * because by the time this round runs javac has already put its own default
     * constructor in the tree - and taking that for an author's would leave the
     * builder with the {@code public} one javac supplies, publishing
     * {@code new Target.Builder()} as a second entry point that
     * {@code builderConstructorAccess} exists to close.
     */
    private static boolean declaresConstructor(JCClassDecl declared) {
        return AllArgsConstructorFactory.hasExplicitConstructor(declared);
    }


    private static String key(JCMethodDecl method) {
        return method.name + "/" + method.params.size();
    }

    private static String signature(JCMethodDecl method) {
        return method.name + "(" + method.params.size() + " args)";
    }

    /** A type-parameter list as it reads in a diagnostic, or {@code none}. */
    private static String names(List<String> parameters) {
        Set<String> out = new LinkedHashSet<>(parameters);
        return out.isEmpty() ? "none" : "<" + String.join(", ", out) + ">";
    }

    /** A declared type stripped of its arguments, for a same-erasure comparison. */
    private static String erasedName(String type) {
        return DeclaredBuilderShape.erasedName(type);
    }

    /**
     * The declared nested type of the builder's name, or {@code null} when the
     * target declares none.
     *
     * @param target the annotated type's declaration
     * @param builderName the builder's resolved simple name
     * @return the declaration, or {@code null}
     */
    static JCClassDecl declaredBuilder(JCClassDecl target, String builderName) {
        for (JCTree def : target.defs) {
            if (def instanceof JCClassDecl nested && nested.name.contentEquals(builderName)) {
                return nested;
            }
        }
        return null;
    }

    /**
     * Whether the target declares a member the merge would have to work around
     * outside the builder itself. Present so the caller can keep the all-args
     * constructor decision in one place.
     *
     * @param targetElement the annotated type
     * @return whether the target declares any constructor of its own
     */
    static boolean declaresOwnConstructor(TypeElement targetElement) {
        for (Element enclosed : targetElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.CONSTRUCTOR
                && !enclosed.getModifiers().contains(Modifier.ABSTRACT)) {
                return true;
            }
        }
        return false;
    }

}
