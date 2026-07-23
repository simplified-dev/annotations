package dev.simplified.args.apt;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a class declaration into the ordered field list a constructor mode
 * selects.
 *
 * <p>Reads the declaration tree rather than the element model for one fact the
 * model cannot supply - whether a field carries an initializer - and the
 * element model for everything else. Walking the tree also fixes the order to
 * declaration order, which is what the positional {@code new Target(...)} calls
 * generated elsewhere line up against.
 */
public final class ArgsFieldSelector {

    private static final String BUILDER_IGNORE_FQN = "dev.simplified.annotations.BuilderIgnore";
    private static final String LAZY_FQN = "dev.simplified.annotations.Lazy";

    private ArgsFieldSelector() {
    }

    /**
     * Every instance field the type declares, in declaration order.
     *
     * <p>{@code static} fields, enum constants and {@code $}-prefixed synthetic
     * fields are dropped. {@code transient} fields are <b>kept</b>: only
     * {@link ArgsMode#BUILDER} has a reason to drop them, and doing it here
     * would silently shorten the constructor of every type a reflective
     * framework populates.
     *
     * @param target the class declaration to walk
     * @param targetElement the same type as an element, for types and annotations
     * @return the candidate fields, in declaration order
     */
    public static List<ArgsField> candidates(JCClassDecl target, TypeElement targetElement) {
        Map<String, VariableElement> elements = fieldElements(targetElement);
        List<ArgsField> out = new ArrayList<>();
        for (JCTree def : target.defs) {
            if (!(def instanceof JCVariableDecl decl)) continue;
            String name = decl.name.toString();
            if (name.startsWith("$")) continue;
            VariableElement element = elements.get(name);
            if (element == null) continue;
            // An enum's constants are JCVariableDecls of the enum type carrying
            // Flags.ENUM. They are not state, and the STATIC test below is not
            // relied on to exclude them.
            if (element.getKind() == ElementKind.ENUM_CONSTANT) continue;
            if ((decl.mods.flags & Flags.STATIC) != 0) continue;
            if (element.getModifiers().contains(Modifier.STATIC)) continue;
            out.add(new ArgsField(
                name,
                element.asType().toString(),
                (decl.mods.flags & Flags.FINAL) != 0
                    || element.getModifiers().contains(Modifier.FINAL),
                decl.init != null,
                hasAnnotation(element, LAZY_FQN),
                element));
        }
        return out;
    }

    /**
     * Applies a mode to the candidate list.
     *
     * @param mode the selection policy
     * @param candidates the output of {@link #candidates}
     * @param builderExclude field names {@code @ClassBuilder(exclude)} removes,
     *        consulted only by {@link ArgsMode#BUILDER}
     * @return the selected fields, in declaration order
     */
    public static List<ArgsField> select(ArgsMode mode, List<ArgsField> candidates,
                                         Set<String> builderExclude) {
        List<ArgsField> out = new ArrayList<>();
        for (ArgsField f : candidates) {
            // A @Lazy field's storage is a Lazy<T> the field itself owns, and
            // the rewrite that installs it also makes the field final. Only the
            // builder's constructor knows how to hand it a supplier instead, so
            // a plain parameter here would assign a final twice.
            if (f.isLazy()) continue;
            boolean isTransient = f.element().getModifiers().contains(Modifier.TRANSIENT);
            boolean ignored = hasAnnotation(f.element(), BUILDER_IGNORE_FQN);
            boolean excluded = builderExclude.contains(f.name());
            if (!ArgsSelection.selects(mode, f.isFinal(), f.hasInitializer(),
                isTransient, ignored, excluded)) continue;
            out.add(f);
        }
        return out;
    }

    /**
     * Fields whose value {@code force = true} has to supply, being the ones a
     * zero-argument constructor would otherwise leave unassigned.
     *
     * @param candidates the output of {@link #candidates}
     * @return the {@code final} fields with no initializer, in declaration order
     */
    public static List<ArgsField> unassignedFinals(List<ArgsField> candidates) {
        List<ArgsField> out = new ArrayList<>();
        for (ArgsField f : candidates) {
            if (f.isLazy()) continue;
            if (f.isRequired()) out.add(f);
        }
        return out;
    }

    /**
     * The candidates no mode selects because {@code @Lazy} owns their storage.
     *
     * @param candidates the output of {@link #candidates}
     * @return the {@code @Lazy} fields, in declaration order
     */
    public static List<ArgsField> lazyFields(List<ArgsField> candidates) {
        List<ArgsField> out = new ArrayList<>();
        for (ArgsField f : candidates) {
            if (f.isLazy()) out.add(f);
        }
        return out;
    }

    /**
     * Whether a generated builder would hold this field.
     *
     * <p>Mirrors the builder's own field collection rather than the selection
     * above: {@code transient}, {@code @BuilderIgnore} and
     * {@code @ClassBuilder(exclude)} each take a field out of the builder, which
     * leaves its declared initializer in place and its storage nobody else's to
     * supply. Read for the one field kind where that distinction decides between
     * a note and an error - a {@code @Lazy} field the builder holds is a blank
     * {@code final} only the builder's constructor can assign.
     *
     * @param field a candidate from {@link #candidates}
     * @param builderExclude field names {@code @ClassBuilder(exclude)} removes
     * @return whether the builder models the field
     */
    public static boolean builderManages(ArgsField field, Set<String> builderExclude) {
        if (field.element().getModifiers().contains(Modifier.TRANSIENT)) return false;
        if (hasAnnotation(field.element(), BUILDER_IGNORE_FQN)) return false;
        return !builderExclude.contains(field.name());
    }

    private static boolean hasAnnotation(Element element, String fqn) {
        for (var mirror : element.getAnnotationMirrors()) {
            if (fqn.equals(mirror.getAnnotationType().toString())) return true;
        }
        return false;
    }

    private static Map<String, VariableElement> fieldElements(TypeElement targetElement) {
        Map<String, VariableElement> out = new LinkedHashMap<>();
        for (Element enclosed : targetElement.getEnclosedElements()) {
            if (!(enclosed instanceof VariableElement field)) continue;
            out.put(field.getSimpleName().toString(), field);
        }
        return out;
    }

}
