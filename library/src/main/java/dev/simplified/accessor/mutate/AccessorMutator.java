package dev.simplified.accessor.mutate;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Names;
import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.Setter;
import dev.simplified.classbuilder.apt.AccessorScheme;
import dev.simplified.shared.javac.ApiStatusAnnotations;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.ContractAnnotations;
import dev.simplified.shared.javac.GeneratedAnnotations;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.JavacTypeFactory;
import dev.simplified.shared.javac.NullnessAnnotations;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Synthesises {@code @Getter} and {@code @Setter} accessors onto a target by
 * javac AST mutation.
 *
 * <p>One mutator serves both annotations. They differ in four places - return
 * type, parameter list, which naming role is read, and which contract is
 * emitted - and share everything else: field collection, precedence, the
 * collision snapshot, annotation propagation and injection. Two classes would
 * have been two copies that drift.
 *
 * <p>Three rules are load bearing:
 *
 * <ul>
 *   <li><b>Only fields the target declares.</b> Walking inherited fields emits a
 *       duplicate accessor on every subclass of an annotated parent.</li>
 *   <li><b>The collision snapshot is taken before anything is appended</b>, and
 *       keyed on name plus argument count. Deciding per class, or matching on
 *       parameter type, turns a hand-written overload into a duplicate-method
 *       error.</li>
 *   <li><b>Annotation propagation is an allowlist.</b> A denylist cannot
 *       enumerate Hibernate, Jackson and Spring, and one leaked annotation
 *       changes what a persistence provider does with the member. The list is
 *       {@link NullnessAnnotations}, shared with every other pass that mints a
 *       member from a field, so the allowlist cannot differ by pass.</li>
 * </ul>
 */
public final class AccessorMutator {

    /**
     * This pass's marker key. Public because it is the only way a later reader
     * can tell an accessor this pass minted from any other zero-arg generated
     * method the pipeline injects - the builder's {@code mutate()} and the
     * equality pass's {@code hashCode()} are both generated, both zero-arg and
     * both instance methods, and either can carry the name of a field.
     */
    public static final String PASS = "accessor";

    /** Which accessor is being synthesised. */
    public enum Kind {

        /** A zero-argument read accessor returning the field's type. */
        GET,

        /** A one-argument write accessor returning {@code void}. */
        SET

    }

    private final JavacBridge bridge;
    private final Messager messager;
    private final TreeMaker make;
    private final Names names;
    private final JavacTypeFactory types;

    public AccessorMutator(JavacBridge bridge, Messager messager) {
        this.bridge = bridge;
        this.messager = messager;
        this.make = bridge.treeMaker();
        this.names = bridge.names();
        this.types = new JavacTypeFactory(make, names);
    }

    /**
     * Runs both accessor passes over one target.
     *
     * @param targetElement the type carrying, or whose fields carry, the annotations
     * @return {@code true} when mutation completed; {@code false} when the
     *         element has no resolvable source tree
     */
    public boolean mutate(TypeElement targetElement) {
        JCClassDecl target = bridge.treeOf(targetElement);
        if (target == null) return false;

        make.at(target.pos);

        // Snapshotted once, before either pass appends anything, so a getter
        // emitted by the first pass cannot be mistaken for one the author wrote
        // when the second pass checks for collisions.
        Set<String> declared = declaredSignatures(target);
        Map<String, VariableElement> elements = fieldElements(targetElement);
        Set<String> lazyFields = lazyFields(elements);

        run(target, targetElement, elements, declared, lazyFields, Kind.GET);
        run(target, targetElement, elements, declared, lazyFields, Kind.SET);
        return true;
    }

    /**
     * Fields carrying {@code @Lazy}, read off the elements rather than threaded
     * in from the pass that rewrote them. The two agree by construction and the
     * annotation is the thing both are really keyed on, so reading it here
     * keeps this mutator usable whatever order the passes run in.
     */
    private static Set<String> lazyFields(Map<String, VariableElement> elements) {
        Set<String> out = new HashSet<>();
        for (var entry : elements.entrySet()) {
            for (var mirror : entry.getValue().getAnnotationMirrors()) {
                if (!"dev.simplified.annotations.Lazy"
                    .equals(mirror.getAnnotationType().toString())) continue;
                out.add(entry.getKey());
            }
        }
        return out;
    }

    private void run(JCClassDecl target, TypeElement targetElement,
                     Map<String, VariableElement> elements, Set<String> declared,
                     Set<String> lazyFields, Kind kind) {
        Resolved typeLevel = readAnnotation(targetElement, kind);
        boolean isEnum = targetElement.getKind() == ElementKind.ENUM;

        for (JCTree def : List.from(target.defs.toArray(new JCTree[0]))) {
            if (!(def instanceof JCVariableDecl decl)) continue;
            String fieldName = decl.name.toString();
            VariableElement element = elements.get(fieldName);
            if (element == null) continue;
            // An enum's constants are fields of the enum type; they are not
            // state and must never grow an accessor.
            if (isEnum && element.getKind() == ElementKind.ENUM_CONSTANT) continue;

            Resolved fieldLevel = readAnnotation(element, kind);
            Resolved effective = fieldLevel != null ? fieldLevel : typeLevel;
            if (effective == null) continue;
            if (fieldLevel == null && typeLevel.excludes(fieldName)) continue;
            if (!effective.access().emits()) continue;

            boolean fromType = fieldLevel == null;
            if (!accepts(decl, element, fieldName, kind, fromType, lazyFields, targetElement)) continue;

            JCMethodDecl accessor = build(decl, element, fieldName, kind, effective);
            String signature = accessor.name.toString() + "/" + accessor.params.size();
            if (!declared.add(signature)) continue;
            bridge.compat().appendDef(target, accessor);
        }
    }

    // ------------------------------------------------------------------
    // Eligibility
    // ------------------------------------------------------------------

    /**
     * Whether the field takes an accessor at all. Rejections that only apply to
     * a field the author singled out are reported; rejections under a
     * type-level annotation are silent, because fanning out over a class is a
     * blanket request rather than a claim about any one field.
     */
    private boolean accepts(JCVariableDecl decl, VariableElement element, String fieldName,
                            Kind kind, boolean fromType, Set<String> lazyFields,
                            TypeElement targetElement) {
        if (fieldName.startsWith("$")) return false;

        // A type-level annotation fans out over the class's state, and a static
        // field holds the class's own rather than any instance's. Without this
        // every constant in an annotated class grows a public accessor as a side
        // effect of annotating the class. Naming the field directly is how a
        // static accessor is asked for, and that route still builds one.
        boolean isStatic = (decl.mods.flags & Flags.STATIC) != 0
            || element.getModifiers().contains(Modifier.STATIC);
        if (isStatic && fromType) return false;

        boolean isFinal = (decl.mods.flags & Flags.FINAL) != 0
            || element.getModifiers().contains(Modifier.FINAL);

        if (lazyFields.contains(fieldName)) {
            if (kind == Kind.GET) {
                // @Lazy already synthesised a getter that unwraps the storage.
                // A second one returning Lazy<T> is a duplicate method javac
                // reports with no source line.
                if (!fromType) {
                    messager.printMessage(Diagnostic.Kind.NOTE,
                        "@Getter on '" + fieldName + "' is redundant - @Lazy already synthesises "
                            + "its accessor", element);
                }
                return false;
            }
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@Setter cannot be combined with @Lazy on '" + fieldName
                    + "' - the field's storage is a Lazy wrapper, so a plain assignment does not "
                    + "type-check", element);
            return false;
        }

        if (kind == Kind.SET && isFinal) {
            if (fromType) return false;
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@Setter cannot be applied to final field '" + fieldName + "'", element);
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Synthesis
    // ------------------------------------------------------------------

    private JCMethodDecl build(JCVariableDecl decl, VariableElement element, String fieldName,
                               Kind kind, Resolved resolved) {
        boolean isStatic = (decl.mods.flags & Flags.STATIC) != 0;
        boolean isBoolean = element.asType().getKind() == TypeKind.BOOLEAN;
        AccessorScheme scheme = AccessorScheme.resolve(resolved.style(), resolved.name());
        String methodName = kind == Kind.GET
            ? scheme.readName(fieldName, isBoolean)
            : scheme.writeName(fieldName, isBoolean);

        String typeDisplay = element.asType().toString();
        long flags = accessFlagFor(resolved.access()) | (isStatic ? Flags.STATIC : 0L);

        ContractAnnotations contracts =
            new ContractAnnotations(make, names, types, resolved.emitContracts());
        GeneratedAnnotations generated =
            new GeneratedAnnotations(make, types, resolved.emitGenerated());

        JCExpression receiver = isStatic
            ? make.Ident(names.fromString(fieldName))
            : make.Select(make.Ident(names._this), names.fromString(fieldName));

        JCMethodDecl method;
        if (kind == Kind.GET) {
            JCBlock body = make.Block(0, List.of(make.Return(receiver)));
            boolean notNull = hasAnnotation(element, "org.jetbrains.annotations.NotNull");
            List<JCAnnotation> anno = notNull
                ? contracts.pureReturnNonNull()
                : contracts.pure();
            // The return type is the field's own, so the field's nullness
            // describes it verbatim - the condition NullnessAnnotations exists
            // to state.
            anno = anno.appendList(NullnessAnnotations.copy(element, make, types));
            // The accessor, not the private field behind it, is what a consumer
            // can reach, so the markings have to sit here to mean anything.
            anno = anno.appendList(ApiStatusAnnotations.copy(element, decl, make));
            method = make.MethodDef(
                make.Modifiers(flags, anno),
                names.fromString(methodName),
                types.parseType(typeDisplay),
                List.nil(),
                List.nil(),
                List.nil(),
                body,
                null
            );
        } else {
            // Same reasoning as the getter's return type: the write accessor
            // takes the field's own type, so the field's nullness is a claim
            // about this parameter and not about some retyped slot.
            JCVariableDecl param = make.VarDef(
                make.Modifiers(Flags.PARAMETER, NullnessAnnotations.copy(element, make, types)),
                names.fromString(fieldName),
                types.parseType(typeDisplay),
                null
            );
            JCStatement assign = make.Exec(make.Assign(
                receiver, make.Ident(names.fromString(fieldName))));
            // A static setter mutates the class, not an instance, so the
            // this-mutation clause would be a lie there.
            List<JCAnnotation> anno = isStatic
                ? contracts.contract(null, false, null)
                : contracts.contract(null, false, "this");
            anno = anno.appendList(ApiStatusAnnotations.copy(element, decl, make));
            method = make.MethodDef(
                make.Modifiers(flags, anno),
                names.fromString(methodName),
                make.TypeIdent(TypeTag.VOID),
                List.nil(),
                List.of(param),
                List.nil(),
                make.Block(0, List.of(assign)),
                null
            );
        }
        AstMarkers.markGenerated(method, generated);
        // The pass mark, not the generated one, is what a reader resolving a
        // member through its accessor may match on: only this pass mints a
        // return type from the very field the body returns.
        AstMarkers.markPass(method, PASS);
        // The accessor's documentation is the field's documentation, and this is
        // the only point where both nodes are in hand.
        AstMarkers.markDocSource(method, decl);
        return method;
    }

    private static boolean hasAnnotation(VariableElement element, String fqn) {
        for (var mirror : element.getAnnotationMirrors()) {
            if (fqn.equals(mirror.getAnnotationType().toString())) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Reading the annotations
    // ------------------------------------------------------------------

    /** One resolved annotation, whichever of the two kinds it came from. */
    private record Resolved(AccessLevel access, NamingStyle style, String name,
                            java.util.List<String> exclude, boolean emitContracts,
                            boolean emitGenerated) {

        boolean excludes(String fieldName) {
            return exclude.contains(fieldName);
        }

    }

    private static Resolved readAnnotation(Element element, Kind kind) {
        if (kind == Kind.GET) {
            Getter a = element.getAnnotation(Getter.class);
            return a == null ? null : new Resolved(a.value(), a.style(), a.name(),
                java.util.List.of(a.exclude()), a.emitContracts(), a.emitGenerated());
        }
        Setter a = element.getAnnotation(Setter.class);
        return a == null ? null : new Resolved(a.value(), a.style(), a.name(),
            java.util.List.of(a.exclude()), a.emitContracts(), a.emitGenerated());
    }

    // ------------------------------------------------------------------
    // Collision
    // ------------------------------------------------------------------

    /** Method name plus argument count for everything the target declares. */
    private static Set<String> declaredSignatures(JCClassDecl target) {
        Set<String> out = new HashSet<>();
        for (JCTree def : target.defs) {
            if (def instanceof JCMethodDecl m) out.add(m.name.toString() + "/" + m.params.size());
        }
        return out;
    }

    /** The target's own fields, by name, so the tree walk can read their types. */
    private static Map<String, VariableElement> fieldElements(TypeElement targetElement) {
        Map<String, VariableElement> out = new LinkedHashMap<>();
        for (Element enclosed : targetElement.getEnclosedElements()) {
            if (!(enclosed instanceof VariableElement field)) continue;
            out.put(field.getSimpleName().toString(), field);
        }
        return out;
    }

    private static long accessFlagFor(AccessLevel access) {
        return switch (access) {
            case PUBLIC -> Flags.PUBLIC;
            case PROTECTED -> Flags.PROTECTED;
            case PRIVATE -> Flags.PRIVATE;
            case PACKAGE -> 0L;
            case NONE -> throw new IllegalStateException("guarded by emits() at the call site");
        };
    }

}
