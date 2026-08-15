package dev.simplified.classbuilder.mutate;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree;
import dev.simplified.classbuilder.apt.FieldSpec;

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
 * <p>Without this the whole injection is skipped: a class declaring a nested
 * type by the builder's name keeps it and gets nothing generated. That is the
 * right default - two builders of one name is not a thing to guess at - but it
 * makes a single {@code apply(GsonContributor)} or a {@code withField(String,
 * String, boolean)} that constructs its own value cost every other setter on
 * the type.
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
     * Reports the two shapes a declared builder cannot take, returning whether
     * the merge may proceed.
     *
     * <p>An inner class captures the enclosing instance, so no {@code static}
     * entry point can create one; and a generic target's members are written in
     * the builder's own re-declared parameters, which have to be there and in
     * the same order for a generated setter to name the slot's type at all.
     */
    private boolean rejectUnusableShape(TypeElement targetElement, JCClassDecl declared) {
        if ((declared.mods.flags & Flags.STATIC) == 0) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@ClassBuilder cannot merge into '" + declared.name + "' - an inner class captures "
                    + "the enclosing instance, so " + ctx.config().builderMethodName()
                    + "() has nothing to create it from. Declare it static",
                targetElement);
            return false;
        }
        com.sun.tools.javac.util.List<JCTypeParameter> expected = ctx.typeParams();
        if (!sameParameterNames(expected, declared.typarams)) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@ClassBuilder cannot merge into '" + declared.name + "' - a static nested builder "
                    + "for a generic target has to re-declare the target's type parameters "
                    + names(expected) + ", and this one declares " + names(declared.typarams),
                targetElement);
            return false;
        }
        return true;
    }

    /**
     * Reports a declared slot field whose type is not the slot's, which the
     * generated setter would otherwise fail to assign on a line the author never
     * wrote.
     *
     * <p>Compared on the rendered declared type rather than through the element
     * model, which is what is available for a tree the round is still building.
     * A false negative here is only a missed diagnostic - javac still refuses
     * the assignment - so the comparison errs towards saying nothing.
     */
    private void rejectMistypedSlots(TypeElement targetElement, JCClassDecl declared) {
        for (JCTree def : declared.defs) {
            if (!(def instanceof JCVariableDecl field)) continue;
            String name = field.name.toString();
            for (FieldSpec slot : ctx.fields()) {
                if (!slot.name.equals(name)) continue;
                if (field.vartype == null) continue;
                if (erasedName(field.vartype.toString()).equals(erasedName(slot.typeDisplay))) continue;
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "@ClassBuilder merged into '" + declared.name + "' finds '" + name
                        + "' declared as " + field.vartype + ", and the slot it stands for is "
                        + slot.typeDisplay + " - the generated setter has nothing to assign it to",
                    targetElement);
            }
        }
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

    /**
     * Whether two type-parameter lists declare the same names in the same order.
     * Names rather than bounds, because the generated members name the
     * parameters and nothing else about them.
     */
    private static boolean sameParameterNames(com.sun.tools.javac.util.List<JCTypeParameter> expected,
                                              com.sun.tools.javac.util.List<JCTypeParameter> declared) {
        int size = declared == null ? 0 : declared.size();
        if (expected.size() != size) return false;
        int index = 0;
        for (JCTypeParameter parameter : declared) {
            if (!expected.get(index++).name.contentEquals(parameter.name.toString())) return false;
        }
        return true;
    }

    private static String names(Iterable<JCTypeParameter> parameters) {
        Set<String> out = new LinkedHashSet<>();
        for (JCTypeParameter parameter : parameters) out.add(parameter.name.toString());
        return out.isEmpty() ? "none" : "<" + String.join(", ", out) + ">";
    }

    /** A declared type stripped of its arguments, for a same-erasure comparison. */
    private static String erasedName(String type) {
        int generics = type.indexOf('<');
        String raw = (generics < 0 ? type : type.substring(0, generics)).trim();
        int dot = raw.lastIndexOf('.');
        return dot < 0 ? raw : raw.substring(dot + 1);
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
