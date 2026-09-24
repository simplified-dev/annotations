package dev.simplified.classbuilder.mutate;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCTypeApply;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree;
import dev.simplified.annotations.ClassBuilder;
import dev.simplified.classbuilder.apt.BuilderConstructorAccess;
import dev.simplified.classbuilder.apt.ChainBuilderReach;
import dev.simplified.classbuilder.apt.ChainMemberIndex;
import dev.simplified.classbuilder.apt.ChainRole;
import dev.simplified.classbuilder.apt.DeclaredBuildMethod;
import dev.simplified.classbuilder.apt.DeclaredBuilderFacts;
import dev.simplified.classbuilder.apt.DeclaredBuilderRejection;
import dev.simplified.classbuilder.apt.DeclaredBuilderShape;
import dev.simplified.classbuilder.apt.FieldSpec;
import dev.simplified.classbuilder.apt.InheritedMethod;
import dev.simplified.classbuilder.apt.RoleExpectation;
import dev.simplified.classbuilder.apt.SetterShape;
import dev.simplified.classbuilder.apt.SlotHolding;
import dev.simplified.shared.apt.AnnotationLookup;
import dev.simplified.shared.javac.AstMarkers;
import org.jetbrains.annotations.Nullable;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Appends the generated builder's members into a {@code Builder} the target
 * already declares, so a builder needing one member the generator cannot
 * express does not have to be written out in full.
 *
 * <p>Runs whenever a class or record target declares a nested type by the
 * builder's name, and whenever the type around an annotated constructor or
 * static factory does, so a single {@code apply(GsonContributor)} or a
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
 *   <li>a <b>method</b> by name and the erasure of each parameter type, which
 *       {@link DeclaredBuilderShape#methodKey} states for both halves - the
 *       signature javac would refuse to see twice. A method under it on the
 *       author's own builder is the setter they wrote instead of the generated
 *       one, which is what merging is for; one sharing only the name and arity
 *       takes another type, is an overload beside the generated setter, and
 *       leaves it to be appended, {@code from(T)} and {@code mutate()} passing
 *       the slot's own type to it;</li>
 *   <li>the builder's <b>constructor</b>, always. The declared class has one by
 *       the time this runs whether the author wrote it or not, javac having
 *       entered a default before the round began, and a second no-arg form
 *       beside either is a duplicate.</li>
 * </ul>
 *
 * <p>Where the author wrote no constructor, the default javac entered takes the
 * generated constructor's place instead: on a class or record target and on a
 * constructor or factory target it is retyped to {@code builderConstructorAccess}
 * and marked generated, so {@code new Target.Builder()} is closed off exactly as
 * it is on a builder the generator writes whole. The retype clears javac's
 * default-constructor flag along with the access bits, on the tree and on the
 * entered symbol, because the tree cleaner between rounds removes a first
 * method still carrying that flag and javac then enters a fresh default at the
 * class's own access. A chain role's builder keeps javac's default, as the
 * builder the chain generates does. Where the author wrote one, it wins, and
 * {@code builderConstructorAccess} written beside it is reported as having no
 * effect.
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
     * @param target the declaration the builder nests in
     * @param anchor the element the annotation is written on, which every
     *     diagnostic is reported against - the type, or the constructor or
     *     factory method
     * @param declared the builder the target declares
     * @param role the target's position in a chain, which decides the shape the
     *     declared builder has to take
     * @param members the generated members to merge, in emission order, as the
     *     producer for the role builds them
     * @param annotatedSuper the target's annotated direct superclass, whose
     *     builder a linked role's declaration has to extend, or {@code null}
     *     when there is none
     * @return whether the merge ran; {@code false} when the declared builder
     *     cannot host the generated members and an error was reported
     */
    boolean merge(JCClassDecl target, Element anchor, JCClassDecl declared, ChainRole role,
                  List<JCTree> members, @Nullable AnnotatedSuper annotatedSuper) {
        if (!rejectUnusableShape(anchor, declared, role, annotatedSuper)) return false;
        // A root's builder may inherit its self() from a supertype, which then
        // stands for one the author declared.
        ChainBuilderReach.SelfMethod inheritedSelf = role == ChainRole.ABSTRACT_ROOT && declared.sym != null
            ? ChainMemberIndex.inheritedSelf(ctx.bridge(), declared.sym)
            : null;
        if (role.isSelfTyped()) rejectUnextendable(anchor, declared, role, inheritedSelf);
        Map<String, JCMethodDecl> methods = declaredMethodKeys(declared);
        rejectMistypedSlots(anchor, declared, members, methods.keySet());
        rejectUnoverridableInheritedMethods(declared, members, methods.keySet());

        Set<String> fields = declaredFieldNames(declared);
        boolean authorOwnsConstruction = declaresConstructor(declared);
        boolean accessApplies = BuilderConstructorAccess.appliesTo(role);
        if (accessApplies && authorOwnsConstruction) warnInertAccess(anchor, declared);
        boolean retyped = accessApplies && retypeDefaultConstructor(declared);

        List<String> skipped = new ArrayList<>();
        for (JCTree member : members) {
            if (member instanceof JCVariableDecl field) {
                if (fields.contains(field.name.toString())) {
                    skipped.add(field.name.toString());
                    continue;
                }
            } else if (member instanceof JCMethodDecl method) {
                // A constructor is never appended here. The declared builder
                // always has one by now - the author's, or the default javac
                // entered before this round - and a second no-arg form beside
                // either is a duplicate. A retyped default already stands in
                // for the generated one, and javac's own default is not one
                // the builder spells - the tree cleaner removes it before the
                // next round - so only a constructor written on the builder,
                // the author's or one an annotation on it appends, is reported.
                if (method.name.contentEquals("<init>")) {
                    if (!retyped && !constructorSignatures(declared).isEmpty()) skipped.add(declared.name + "(..)");
                    continue;
                }
                // The generated self() a root's builder inherits is not appended
                // beside the inherited one, which it would override abstract
                // and, beside a public one, with weaker access.
                if (method.name.contentEquals(ChainBuilderReach.SELF) && method.params.isEmpty()
                    && !ChainBuilderReach.appendsSelf(inheritedSelf)) {
                    continue;
                }
                JCMethodDecl author = methods.get(key(declared, method));
                if (author != null) {
                    skipped.add(signature(method));
                    SetterShape shape = ctx.setterShape(method);
                    if (shape != null) {
                        ctx.recordCoveredSetter(new CoveredSetter(method.name.toString(), shape,
                            parameterTypes(author), parameterTypes(method)));
                    }
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
                anchor);
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
     *
     * @param anchor the element every diagnostic is reported against
     * @param declared the builder the target declares
     * @param role the target's position in a chain
     * @param annotatedSuper the target's annotated direct superclass, or {@code null}
     * @return whether the merge may proceed
     */
    private boolean rejectUnusableShape(Element anchor, JCClassDecl declared, ChainRole role,
                                        @Nullable AnnotatedSuper annotatedSuper) {
        DeclaredBuilderFacts facts = factsOf(declared);
        RoleExpectation expectation = expectationFor(role, facts, annotatedSuper);
        DeclaredBuilderRejection rejection = DeclaredBuilderShape.check(role, facts, expectation);
        if (rejection == null) return true;
        messager.printMessage(Diagnostic.Kind.ERROR,
            DeclaredBuilderShape.describe(rejection, role, declared.name.toString(),
                ctx.targetSimpleName(), ctx.config().builderMethodName(), facts, expectation),
            anchor);
        return false;
    }

    /**
     * Reports a builder declared on a self-typed role that the builders
     * generated below it cannot extend or override into.
     *
     * <p>The decisions and their wording are
     * {@link ChainBuilderReach#unextendableBuilder},
     * {@link ChainBuilderReach#finalSelf} and
     * {@link ChainBuilderReach#mistypedInheritedSelf}, which the editor's
     * inspection asks of the same facts read out of PSI. Reported on the
     * declared builder, where the author acts; the merge continues, since the
     * builder itself is sound and only a link below it is not, and each link is
     * refused on its own annotation. An inherited {@code self()} returning
     * another type than the pair's builder parameter fails the generated
     * setters as well, which the report stands for.
     *
     * @param anchor the element the annotation is written on, reported against when the builder has no symbol
     * @param declared the builder the author wrote
     * @param role the target's position in the chain, a self-typed one
     * @param inheritedSelf the {@code self()} a root's builder inherits, or null when it inherits none or is
     *     not a root's
     */
    private void rejectUnextendable(Element anchor, JCClassDecl declared, ChainRole role,
                                    ChainBuilderReach.@Nullable SelfMethod inheritedSelf) {
        Element builder = declared.sym == null ? anchor : declared.sym;
        String declaredName = declared.name.toString();
        String unextendable = ChainBuilderReach.unextendableBuilder(declaredName, ctx.targetSimpleName(),
            constructorSignatures(declared));
        if (unextendable != null) messager.printMessage(Diagnostic.Kind.ERROR, unextendable, builder);
        ChainBuilderReach.SelfMethod declaredSelf = null;
        for (JCTree def : declared.defs) {
            if (def instanceof JCMethodDecl method && method.name.contentEquals(ChainBuilderReach.SELF)
                && method.params.isEmpty() && !AstMarkers.isGenerated(method)) {
                declaredSelf = new ChainBuilderReach.SelfMethod((method.mods.flags & Flags.PUBLIC) != 0,
                    (method.mods.flags & Flags.FINAL) != 0);
            }
        }
        ChainBuilderReach.SelfMethod self = ChainBuilderReach.rootSelf(declaredSelf, inheritedSelf);
        String finalSelf = ChainBuilderReach.finalSelf(declaredName, ctx.targetSimpleName(),
            self != null && self.isFinal());
        if (finalSelf != null) messager.printMessage(Diagnostic.Kind.ERROR, finalSelf, builder);
        if (declaredSelf != null) return;
        String selfBuilder = DeclaredBuilderShape.selfNames(role, targetParameterNames(ctx),
            declaredParameterNames(declared)).get(1);
        String mistyped = ChainBuilderReach.mistypedInheritedSelf(declaredName, selfBuilder, inheritedSelf);
        if (mistyped != null) messager.printMessage(Diagnostic.Kind.ERROR, mistyped, builder);
    }

    /**
     * The parameter types of each constructor the declared builder declares, as
     * written - the author's, and each one a constructor annotation written on
     * it appended in the pass before this one - leaving out the default javac
     * entered for a class declaring none.
     *
     * @param declared the builder the author wrote
     * @return each constructor's parameter types, in declaration order
     */
    private static List<List<String>> constructorSignatures(JCClassDecl declared) {
        List<List<String>> out = new ArrayList<>();
        for (JCTree def : declared.defs) {
            if (!(def instanceof JCMethodDecl method) || !method.name.contentEquals("<init>")) continue;
            if ((method.mods.flags & Flags.GENERATEDCONSTR) != 0) continue;
            out.add(parameterTypes(method));
        }
        return out;
    }

    /**
     * What the role requires of the declared builder.
     *
     * <p>The derivation is {@link DeclaredBuilderShape#expectation}, which the
     * editor asks of the same names read out of PSI; what is left here is
     * reading them off the context - the target's own parameters and the types
     * its extends and implements clauses name, and on a linked role the
     * ancestor's simple name and the arguments the target passes it.
     *
     * @param role the target's position in a chain
     * @param facts the declared builder as written
     * @param annotatedSuper the target's annotated direct superclass, or {@code null}
     * @return the parameter names, supertype, build return type and supertype arguments to measure against
     */
    private RoleExpectation expectationFor(ChainRole role, DeclaredBuilderFacts facts,
                                           @Nullable AnnotatedSuper annotatedSuper) {
        List<String> supertypes = new ArrayList<>();
        if (ctx.target().extending != null) supertypes.add(ctx.target().extending.toString());
        for (JCExpression implemented : ctx.target().implementing) supertypes.add(implemented.toString());
        return DeclaredBuilderShape.expectation(role, ctx.targetSimpleName(), ctx.builderName(),
            targetParameterNames(ctx), boundsOf(ctx.typeParams()), facts.typeParameterNames(),
            annotatedSuper == null ? null : annotatedSuper.simpleName(),
            annotatedSuper == null ? List.of() : annotatedSuper.typeArguments(), supertypes);
    }

    /**
     * The names of the type parameters a builder for this target re-declares.
     *
     * @param ctx the per-target mutation context
     * @return the names, in declaration order
     */
    static List<String> targetParameterNames(MutationContext ctx) {
        List<String> out = new ArrayList<>();
        for (JCTypeParameter parameter : ctx.typeParams()) out.add(parameter.name.toString());
        return out;
    }

    /**
     * The bounds written on each type parameter, joined as
     * {@link DeclaredBuilderFacts#typeParameterBounds} holds them.
     *
     * @param parameters the parameters to read, the target's or the declared builder's
     * @return the bounds, in declaration order, null where a parameter has none
     */
    static List<String> boundsOf(@Nullable Iterable<JCTypeParameter> parameters) {
        List<String> out = new ArrayList<>();
        if (parameters == null) return out;
        for (JCTypeParameter parameter : parameters) {
            if (parameter.bounds == null || parameter.bounds.isEmpty()) {
                out.add(null);
                continue;
            }
            List<String> bounds = new ArrayList<>();
            for (JCExpression bound : parameter.bounds) bounds.add(bound.toString());
            out.add(String.join(" & ", bounds));
        }
        return out;
    }

    /**
     * The names of the type parameters a declared builder declares.
     *
     * @param declared the builder the author wrote
     * @return the names, in declaration order
     */
    static List<String> declaredParameterNames(JCClassDecl declared) {
        List<String> out = new ArrayList<>();
        if (declared.typarams == null) return out;
        for (JCTypeParameter parameter : declared.typarams) out.add(parameter.name.toString());
        return out;
    }

    /**
     * Reads the declared builder as written, taking nothing from the element
     * model - the round is still building this tree.
     *
     * @param declared the builder the author wrote
     * @return the facts the shape decision measures
     */
    private DeclaredBuilderFacts factsOf(JCClassDecl declared) {
        List<String> parameterNames = declaredParameterNames(declared);
        List<String> parameterBounds = boundsOf(declared.typarams);
        String writtenSuper = declared.extending == null
            ? null
            : DeclaredBuilderShape.rawType(declared.extending.toString());
        List<String> superArguments = new ArrayList<>();
        if (declared.extending instanceof JCTypeApply applied) {
            for (JCExpression argument : applied.arguments) superArguments.add(argument.toString());
        }
        return new DeclaredBuilderFacts((declared.mods.flags & Flags.STATIC) != 0,
            (declared.mods.flags & Flags.ABSTRACT) != 0,
            parameterNames, parameterBounds, writtenSuper, superArguments,
            declaredBuildMethod(declared), kindOf(declared));
    }

    /**
     * The keyword the declared type is written with, read off the flags the
     * parser sets for it - an implicit {@code static} on a member record, enum
     * or interface is recorded on the symbol only, which is why the kind is
     * asked rather than the modifier.
     *
     * @param declared the type the author wrote
     * @return one of the {@link DeclaredBuilderFacts} kind constants
     */
    private static String kindOf(JCClassDecl declared) {
        long flags = declared.mods.flags;
        if ((flags & Flags.ANNOTATION) != 0) return DeclaredBuilderFacts.ANNOTATION;
        if ((flags & Flags.INTERFACE) != 0) return DeclaredBuilderFacts.INTERFACE;
        if ((flags & Flags.ENUM) != 0) return DeclaredBuilderFacts.ENUM;
        if ((flags & Flags.RECORD) != 0) return DeclaredBuilderFacts.RECORD;
        return DeclaredBuilderFacts.CLASS;
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
     * Reports each declared slot field whose type is not the one the builder
     * holds that slot in.
     *
     * <p>The comparison and its wording are
     * {@link DeclaredBuilderShape#mistypedSlot}, which the editor's inspection
     * asks of the same strings read out of PSI. What is left here is classifying
     * each slot's storage from the tree, through {@link SlotHolding#of}, which
     * the editor asks of the same facts.
     *
     * <p>A field declared {@code final} is reported too, through
     * {@link DeclaredBuilderShape#finalSlot}, whatever its type, where a setter
     * generated for its slot that assigns it is left to be appended - the
     * author spelling no method under its key. The setters are told apart in
     * the member list by the slot {@link MutationContext#setterSlot} recorded
     * for each, keyed as the collision rule keys them, and each carries the
     * shape {@link MutationContext#setterShape} recorded, which says whether it
     * assigns the field.
     *
     * <p>The merge continues after a report, so javac also refuses the generated
     * member that assigns the slot - the report is what says why on a line the
     * author wrote.
     *
     * @param anchor the element every diagnostic is reported against
     * @param declared the builder the author wrote
     * @param members the generated members being merged
     * @param authorKeys the {@link DeclaredBuilderShape#methodKey} of each method the author declared
     */
    private void rejectMistypedSlots(Element anchor, JCClassDecl declared, List<JCTree> members,
                                     Set<String> authorKeys) {
        for (JCTree def : declared.defs) {
            if (!(def instanceof JCVariableDecl field)) continue;
            if (field.vartype == null) continue;
            String name = field.name.toString();
            for (FieldSpec slot : ctx.fields()) {
                if (!slot.name.equals(name)) continue;
                String finalSlot = (field.mods.flags & Flags.FINAL) == 0
                    ? null
                    : DeclaredBuilderShape.finalSlot(declared.name.toString(), name,
                        setters(declared, members, name), slot.append, authorKeys);
                if (finalSlot != null) {
                    messager.printMessage(Diagnostic.Kind.ERROR, finalSlot, anchor);
                    continue;
                }
                SlotHolding holding = holdingOf(slot);
                String message = DeclaredBuilderShape.mistypedSlot(declared.name.toString(), name,
                    field.vartype.toString(), storageType(slot, holding), holding);
                if (message != null) messager.printMessage(Diagnostic.Kind.ERROR, message, anchor);
            }
        }
    }

    /**
     * Reports each generated setter the merge appends that a method the
     * declared builder inherits keeps from overriding it.
     *
     * <p>The decision and its wording are
     * {@link DeclaredBuilderShape#unoverridableInheritedMethod}, which the
     * editor's inspection asks of the same facts read out of resolved PSI. What
     * is left here is reading the inherited methods from the element model -
     * see {@link #inheritedMethods} - and asking it for every setter no author
     * method covers, the ones that are appended.
     *
     * <p>Reported on the declared builder, where the author declared the
     * supertype. The merge continues, as after a mistyped slot, so javac also
     * refuses the appended setter; the report is what says why on a line the
     * author wrote.
     *
     * @param declared the builder the author wrote
     * @param members the generated members being merged
     * @param authorKeys the {@link DeclaredBuilderShape#methodKey} of each method the author declared
     */
    private void rejectUnoverridableInheritedMethods(JCClassDecl declared, List<JCTree> members,
                                                     Set<String> authorKeys) {
        TypeElement builder = declared.sym;
        if (builder == null) return;
        Map<String, String> erasures = DeclaredBuilderShape.typeVariableErasures(declaredParameterNames(declared),
            boundsOf(declared.typarams));
        List<InheritedMethod> inherited = null;
        for (JCTree member : members) {
            if (!(member instanceof JCMethodDecl method) || ctx.setterSlot(method) == null) continue;
            if (authorKeys.contains(key(declared, method))) continue;
            if (inherited == null) inherited = inheritedMethods(builder);
            String message = DeclaredBuilderShape.unoverridableInheritedMethod(declared.name.toString(),
                method.name.toString(), parameterTypes(method), erasures, inherited);
            if (message != null) messager.printMessage(Diagnostic.Kind.ERROR, message, builder);
        }
    }

    /**
     * The methods a declared builder inherits, read from the element model.
     *
     * <p>Its supertypes are walked depth first, each superclass ahead of the
     * interfaces beside it, with {@code java.lang.Object} read last; a private
     * method, a package-private one declared in another package, and a static
     * one an interface declares are not inherited and are left out. A static
     * method of a superclass is read, flagged static, since the setter cannot
     * override it either. Each method is read as a member of the builder, so a
     * self-typed supertype's {@code B} is the builder itself, and its return
     * type accepts the builder where the builder is assignable to it or to its
     * erasure. Its parameters are read both erased and as they stand as members
     * of the builder, where a type variable the builder passes the supertype
     * keeps its name.
     *
     * <p>The element model holds what the supertype's source declares: a
     * supertype compiled in the same round is read through the members written
     * in it, and one compiled before it through its class file. A member this
     * processor appends to a supertype in the round is not yet entered and is
     * not read.
     *
     * @param builder the declared builder's element
     * @return the inherited methods, in the order the supertypes are walked
     */
    private List<InheritedMethod> inheritedMethods(TypeElement builder) {
        Types types = ctx.bridge().processingEnvironment().getTypeUtils();
        Elements elements = ctx.bridge().processingEnvironment().getElementUtils();
        DeclaredType builderType = (DeclaredType) builder.asType();
        PackageElement home = elements.getPackageOf(builder);

        List<TypeElement> supertypes = new ArrayList<>();
        collectSupertypes(types, builderType, supertypes, new HashSet<>());
        TypeElement object = elements.getTypeElement(Object.class.getName());
        if (object != null) supertypes.add(object);

        List<InheritedMethod> out = new ArrayList<>();
        for (TypeElement supertype : supertypes) {
            boolean samePackage = elements.getPackageOf(supertype).equals(home);
            boolean isInterface = supertype.getKind().isInterface();
            for (ExecutableElement method : ElementFilter.methodsIn(supertype.getEnclosedElements())) {
                Set<Modifier> modifiers = method.getModifiers();
                boolean isStatic = modifiers.contains(Modifier.STATIC);
                if (modifiers.contains(Modifier.PRIVATE) || (isStatic && isInterface)) continue;
                if (!samePackage && !modifiers.contains(Modifier.PUBLIC) && !modifiers.contains(Modifier.PROTECTED))
                    continue;
                ExecutableType member = (ExecutableType) types.asMemberOf(builderType, method);
                List<String> parameters = new ArrayList<>();
                List<String> memberParameters = new ArrayList<>();
                for (TypeMirror parameter : member.getParameterTypes()) {
                    parameters.add(types.erasure(parameter).toString());
                    memberParameters.add(parameter.toString());
                }
                TypeMirror returned = member.getReturnType();
                boolean accepts = returned.getKind() != TypeKind.VOID && !returned.getKind().isPrimitive()
                    && (types.isAssignable(builderType, returned)
                        || types.isAssignable(builderType, types.erasure(returned)));
                out.add(new InheritedMethod(method.getSimpleName().toString(), parameters,
                    supertype.getSimpleName().toString(), method.getReturnType().toString(),
                    modifiers.contains(Modifier.FINAL), accepts, isStatic, memberParameters));
            }
        }
        return out;
    }

    /**
     * Collects every supertype of a type but {@code java.lang.Object}, depth
     * first, each once.
     *
     * @param types the type utilities
     * @param type the type whose supertypes are collected
     * @param out the supertypes collected so far, appended to
     * @param seen the qualified names already collected
     */
    private static void collectSupertypes(Types types, TypeMirror type, List<TypeElement> out, Set<String> seen) {
        for (TypeMirror direct : types.directSupertypes(type)) {
            if (!(direct instanceof DeclaredType declaredType)
                || !(declaredType.asElement() instanceof TypeElement element)) continue;
            String name = element.getQualifiedName().toString();
            if (Object.class.getName().equals(name) || !seen.add(name)) continue;
            out.add(element);
            collectSupertypes(types, direct, out, seen);
        }
    }

    /**
     * The form the generated builder holds the slot in, which is not always the
     * type the field is declared with.
     *
     * <p>Three shapes, matching {@code FieldMutators} exactly: a collected field
     * whose default reads instance state gathers into a plain {@code java.util}
     * scratch container, a lazy field or one whose default is computed on the
     * instance is held as a supplier so the slot can carry "unset" without
     * ambiguity, and everything else is held as declared.
     *
     * @param slot the slot being merged
     * @return how the slot is held
     */
    private SlotHolding holdingOf(FieldSpec slot) {
        return SlotHolding.of(slot.lazy, MutationContext.isCollected(slot), ctx.isInstanceDefault(slot.name));
    }

    /**
     * The type the generated builder declares the slot as.
     *
     * <p>A scratch container is rendered through
     * {@link DeclaredBuilderShape#scratchContainerOf} over the same arguments
     * {@link MutationContext#collectedSlotType} builds it from, which is the
     * rendering the editor asks of the arguments it reads out of PSI.
     *
     * @param slot the slot being merged
     * @param holding how the slot is held
     * @return the storage type, rendered
     */
    private String storageType(FieldSpec slot, SlotHolding holding) {
        if (holding == SlotHolding.COLLECTED_SCRATCH) {
            return DeclaredBuilderShape.scratchContainerOf(slot.isMap, slot.isSet, slot.collectionElement,
                slot.mapKey, slot.mapValue);
        }
        if (holding.isSupplier()) return DeclaredBuilderShape.supplierOf(slot.typeDisplay);
        return slot.typeDisplay;
    }

    /** Field names the declared builder already spells. */
    private static Set<String> declaredFieldNames(JCClassDecl declared) {
        Set<String> out = new HashSet<>();
        for (JCTree def : declared.defs) {
            if (def instanceof JCVariableDecl field) out.add(field.name.toString());
        }
        return out;
    }

    /**
     * The methods the declared builder already spells, by their
     * {@link DeclaredBuilderShape#methodKey}.
     *
     * @param declared the builder the author wrote
     * @return each key with the first method declared under it
     */
    static Map<String, JCMethodDecl> declaredMethodKeys(JCClassDecl declared) {
        Map<String, JCMethodDecl> out = new HashMap<>();
        for (JCTree def : declared.defs) {
            if (def instanceof JCMethodDecl method && !method.name.contentEquals("<init>"))
                out.putIfAbsent(key(declared, method), method);
        }
        return out;
    }

    /**
     * The {@link DeclaredBuilderShape#methodKey} of each setter generated for a
     * slot, with its shape.
     *
     * @param declared the builder the author wrote, whose type variables the keys erase
     * @param members the generated members being merged
     * @param slotName the slot's name
     * @return each key with its setter's shape, in emission order
     */
    private Map<String, SetterShape> setters(JCClassDecl declared, List<JCTree> members, String slotName) {
        Map<String, SetterShape> out = new LinkedHashMap<>();
        for (JCTree member : members) {
            if (member instanceof JCMethodDecl method && slotName.equals(ctx.setterSlot(method)))
                out.put(key(declared, method), ctx.setterShape(method));
        }
        return out;
    }

    /**
     * A setter the merge leaves out because an author method covers it under
     * {@link DeclaredBuilderShape#methodKey}, which the copy entry points pass the
     * slot to instead where its shape is the one they call.
     *
     * @param name the method name the two share
     * @param shape the covered setter's shape
     * @param writtenTypes each parameter type of the author's method as written
     * @param generatedTypes each parameter type of the generated setter as rendered
     */
    record CoveredSetter(String name, SetterShape shape, List<String> writtenTypes,
                         List<String> generatedTypes) { }

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

    /**
     * Retypes the default constructor javac entered into a declared builder
     * that has no other, to {@code builderConstructorAccess}.
     *
     * <p>The recipe {@code @UtilityClass} uses on its target's default: the
     * access bits and {@link Flags#GENERATEDCONSTR} are cleared on the tree and
     * on the entered symbol, the configured access is set on both, and the
     * constructor is marked generated so the author-constructor test keeps
     * reading it as not the author's. Clearing the flag is what makes the retype
     * survive the next round, whose tree cleaner removes a first method still
     * carrying it and lets javac enter a fresh default at the class's access.
     *
     * <p>Nothing is retyped beside any other constructor, the author's or one
     * this pipeline appended: the default would become a second no-argument
     * constructor once the flag is gone, where the cleaner would otherwise have
     * removed it.
     *
     * @param declared the builder the author wrote
     * @return whether the default was found and retyped
     */
    private boolean retypeDefaultConstructor(JCClassDecl declared) {
        JCMethodDecl implicit = null;
        for (JCTree def : declared.defs) {
            if (!(def instanceof JCMethodDecl method) || !method.name.contentEquals("<init>")) continue;
            if ((method.mods.flags & Flags.GENERATEDCONSTR) == 0) return false;
            implicit = method;
        }
        if (implicit == null) return false;

        long cleared = Flags.PUBLIC | Flags.PROTECTED | Flags.PRIVATE | Flags.GENERATEDCONSTR;
        long access = MutationContext.accessFlagFor(ctx.config().builderConstructorAccess());
        implicit.mods.flags = (implicit.mods.flags & ~cleared) | access;
        if (implicit.sym != null) implicit.sym.flags_field = (implicit.sym.flags_field & ~cleared) | access;
        AstMarkers.markGenerated(implicit, ctx.generated());
        return true;
    }

    /**
     * Warns that {@code builderConstructorAccess}, written on the annotation,
     * changes nothing because the declared builder declares its own constructor.
     *
     * <p>A warning rather than a note: the attribute is defeated outright rather
     * than made redundant, as {@code @UtilityClass} warns when an author's
     * constructor defeats its own. Silent when the attribute is not written, the
     * default being no request at all.
     *
     * @param anchor the element the annotation is written on
     * @param declared the builder the author wrote
     */
    private void warnInertAccess(Element anchor, JCClassDecl declared) {
        AnnotationLookup lookup = new AnnotationLookup();
        String annotation = ClassBuilder.class.getName();
        if (lookup.stringAttr(anchor, annotation, BuilderConstructorAccess.ATTRIBUTE, null) == null) return;
        messager.printMessage(Diagnostic.Kind.WARNING,
            BuilderConstructorAccess.hasNoEffect(declared.name.toString()),
            anchor, lookup.findMirror(anchor, annotation));
    }


    /**
     * The {@link DeclaredBuilderShape#methodKey} of a method the declared
     * builder declares or has merged into it, a parameter typed by one of the
     * builder's own type variables keyed by that variable's erasure.
     *
     * @param declared the builder the author wrote
     * @param method the method to key
     * @return its key
     */
    private static String key(JCClassDecl declared, JCMethodDecl method) {
        return DeclaredBuilderShape.methodKey(method.name.toString(), parameterTypes(method),
            DeclaredBuilderShape.typeVariableErasures(declaredParameterNames(declared), boundsOf(declared.typarams)));
    }

    /** Each parameter's type as the tree spells it, in order. */
    static List<String> parameterTypes(JCMethodDecl method) {
        List<String> types = new ArrayList<>(method.params.size());
        for (JCVariableDecl parameter : method.params)
            types.add(parameter.vartype == null ? "" : parameter.vartype.toString());
        return types;
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
