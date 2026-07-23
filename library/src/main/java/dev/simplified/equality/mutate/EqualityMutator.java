package dev.simplified.equality.mutate;

import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree.Tag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import dev.simplified.accessor.apt.AccessorReads;
import dev.simplified.annotations.EqualsAndHashCode;
import dev.simplified.equality.apt.EqualityConfig;
import dev.simplified.equality.apt.MemberWarnings;
import dev.simplified.shared.apt.AnnotationLookup;
import dev.simplified.shared.apt.MemberShape;
import dev.simplified.shared.apt.MemberSpec;
import dev.simplified.shared.apt.SuperResolver;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.ContractAnnotations;
import dev.simplified.shared.javac.GeneratedAnnotations;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.JavacTypeFactory;
import dev.simplified.shared.javac.MemberTerms;

import javax.annotation.processing.Messager;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Injects {@code equals} and {@code hashCode} into a class or record carrying
 * {@code @EqualsAndHashCode}.
 *
 * <p>Records are the shape this exists for, and they work by the same mechanism
 * classes do: JLS 8.10.3 synthesises a record's implicit pair only when the
 * body does not declare it, so a pair appended during an annotation-processing
 * round is the pair that survives. The implicit one compares an array component
 * by reference, which is the defect being closed.
 *
 * <p>The hash is a {@code result * PRIME + term} accumulator rather than
 * {@code Objects.hash(...)}, and the reason is correctness rather than
 * allocation: {@code Objects.hash} takes {@code Object...}, so an array
 * argument hashes by identity and any type with an array member would get a
 * hash inconsistent with the {@code equals} emitted beside it.
 */
public final class EqualityMutator {

    private static final String PASS = "equality";
    private static final String PARAM = "o";
    private static final String OTHER = "other";
    private static final String CACHED = "cached";
    private static final String RESULT = "result";
    private static final String PRIME_NAME = "PRIME";
    private static final String CACHE_FIELD = "$hashCode";
    private static final String CAN_EQUAL = "canEqual";
    private static final String OBJECT_FQN = "java.lang.Object";

    /**
     * The multiplier the accumulator steps by.
     *
     * <p>Any odd prime works; this one is Lombok's, which costs nothing to
     * match and means a type migrating off it keeps the same hash rather than
     * changing it twice.
     */
    private static final int PRIME_VALUE = 59;

    private final JavacBridge bridge;
    private final Types typeUtils;
    private final Messager messager;
    private final AnnotationLookup lookup;
    private final TreeMaker make;
    private final Names names;
    private final JavacTypeFactory types;
    private final MemberTerms terms;

    public EqualityMutator(JavacBridge bridge, Types typeUtils, AnnotationLookup lookup,
                           Messager messager) {
        this.bridge = bridge;
        this.typeUtils = typeUtils;
        this.lookup = lookup;
        this.messager = messager;
        this.make = bridge.treeMaker();
        this.names = bridge.names();
        this.types = new JavacTypeFactory(make, names);
        this.terms = new MemberTerms(make, names, types);
    }

    /** One member, with the read and the emission row already settled. */
    private record Resolved(MemberSpec spec, AccessorReads.Read read, MemberShape shape) {
    }

    /**
     * Injects the pair.
     *
     * @param targetElement the annotated type
     * @param config the resolved attributes
     * @param members the selected members, in emission order
     * @return false when the target has no resolvable source tree
     */
    public boolean mutate(TypeElement targetElement, EqualityConfig config,
                          java.util.List<MemberSpec> members) {
        JCClassDecl target = bridge.treeOf(targetElement);
        if (target == null) return false;
        if (AstMarkers.isPassMarked(target, PASS)) return true;
        AstMarkers.markPass(target, PASS);
        make.at(target.pos);

        if (collides(targetElement, target)) return true;
        if (finalInSupertype(targetElement)) return true;
        if (disagreesWithSuper(targetElement, config)) return true;

        boolean record = targetElement.getKind() == ElementKind.RECORD;
        boolean cache = resolveCache(targetElement, config, record, members);
        boolean callSuper = SuperResolver.resolve(targetElement, config.callSuper(),
            EqualityConfig.FQN, EqualityConfig.LABEL,
            new SuperResolver.Member[]{SuperResolver.Member.EQUALS, SuperResolver.Member.HASH_CODE},
            lookup, messager);
        if (cache && callSuper) {
            messager.printMessage(Diagnostic.Kind.WARNING,
                EqualityConfig.LABEL + "(cacheHashCode) with callSuper - the superclass's "
                    + "contribution may change after construction and its finality is not "
                    + "decidable here, so the memo can go stale",
                targetElement);
        }

        java.util.List<Resolved> resolved = new ArrayList<>(members.size());
        for (MemberSpec member : members) {
            MemberWarnings.report(member, targetElement, messager);
            AccessorReads.Read read = AccessorReads.resolve(targetElement, member,
                config.useAccessors(), typeUtils, EqualityConfig.LABEL, messager);
            resolved.add(new Resolved(member, read, MemberShape.of(read.type())));
        }
        if (resolved.isEmpty() && !callSuper) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                EqualityConfig.LABEL + " on " + targetElement.getSimpleName()
                    + " selected no members - every instance of it compares equal to every other",
                targetElement);
        }

        ContractAnnotations contracts =
            new ContractAnnotations(make, names, types, config.emitContracts());
        GeneratedAnnotations generated =
            new GeneratedAnnotations(make, types, config.emitGenerated());

        Hook hook = resolveHook(targetElement, target, config, record);
        if (cache) bridge.compat().appendDef(target, cacheField(generated));
        bridge.compat().appendDef(target, buildEquals(targetElement, target, config, callSuper,
            hook.call(), resolved, contracts, generated));
        bridge.compat().appendDef(target,
            buildHashCode(callSuper, cache, resolved, contracts, generated));
        if (hook.emit()) {
            bridge.compat().appendDef(target, buildCanEqual(targetElement, target, contracts, generated));
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Refusals
    // ------------------------------------------------------------------

    /**
     * Whether the author already wrote either member.
     *
     * <p>An error rather than the silent skip {@code @ClassBuilder} and
     * {@code @Getter} both perform. Those two decline to supply a member the
     * author can still see and call; an annotation asking for an equality
     * relation that then supplies none leaves the type with a relation nobody
     * wrote down.
     */
    private boolean collides(TypeElement targetElement, JCClassDecl target) {
        Set<String> declared = authorSignatures(target);
        boolean equals = declared.contains("equals/1");
        boolean hashCode = declared.contains("hashCode/0");
        if (!equals && !hashCode) return false;
        String written = equals && hashCode ? "equals and hashCode" : equals ? "equals" : "hashCode";
        messager.printMessage(Diagnostic.Kind.ERROR,
            EqualityConfig.LABEL + " on " + targetElement.getSimpleName() + ", which already "
                + "declares " + written + " - generating the pair would leave the type with an "
                + "equality relation that is half written down and half not. Remove the "
                + "annotation, or remove the members it is meant to supply",
            targetElement);
        return true;
    }

    /** Whether a supertype declares either member {@code final}, which no override can compile past. */
    private boolean finalInSupertype(TypeElement targetElement) {
        for (SuperResolver.Member member : new SuperResolver.Member[]{
            SuperResolver.Member.EQUALS, SuperResolver.Member.HASH_CODE}) {
            TypeElement owner = SuperResolver.finalSupertypeMember(targetElement, member);
            if (owner == null) continue;
            messager.printMessage(Diagnostic.Kind.ERROR,
                EqualityConfig.LABEL + " cannot generate " + member.name() + " on "
                    + targetElement.getSimpleName() + " - " + owner.getQualifiedName()
                    + " declares it final",
                targetElement);
            return true;
        }
        return false;
    }

    /**
     * Whether an annotated supertype asks for a different identity relation.
     *
     * <p>A hierarchy split across two relations is asymmetric with nothing to
     * complain about it, so the disagreement is reported where both halves are
     * still visible.
     */
    private boolean disagreesWithSuper(TypeElement targetElement, EqualityConfig config) {
        for (TypeElement current = SuperResolver.callableSuperclass(targetElement);
             current != null;
             current = SuperResolver.callableSuperclass(current)) {
            EqualsAndHashCode above = current.getAnnotation(EqualsAndHashCode.class);
            if (above == null) continue;
            if (above.identity() == config.identity()) return false;
            messager.printMessage(Diagnostic.Kind.ERROR,
                EqualityConfig.LABEL + "(identity = " + config.identity() + ") on "
                    + targetElement.getSimpleName() + " disagrees with " + current.getSimpleName()
                    + ", which asks for " + above.identity() + " - one hierarchy cannot hold two "
                    + "identity relations without the comparison becoming asymmetric",
                targetElement);
            return true;
        }
        return false;
    }

    /**
     * Whether the hash memo is emitted, refusing it on a record.
     *
     * <p>A record body cannot declare an instance field, so there is nowhere to
     * put the memo. The restriction can in fact be stepped around by injecting
     * the field the same way the method pair is injected - but that is slipping
     * past a closed door rather than using the seam JLS 8.10.3 leaves open for
     * the methods, and it is refused on that difference.
     */
    private boolean resolveCache(TypeElement targetElement, EqualityConfig config, boolean record,
                                 java.util.List<MemberSpec> members) {
        if (!config.cacheHashCode()) return false;
        if (record) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                EqualityConfig.LABEL + "(cacheHashCode) on the record "
                    + targetElement.getSimpleName() + " - a record body cannot declare the "
                    + "instance field the memo needs. Drop cacheHashCode, or make the type a "
                    + "final class",
                targetElement);
            return false;
        }
        for (MemberSpec member : members) {
            if (!member.mutable()) continue;
            messager.printMessage(Diagnostic.Kind.WARNING,
                EqualityConfig.LABEL + "(cacheHashCode) with the mutable member '" + member.name()
                    + "' - the memo is only sound while every compared member is fixed at "
                    + "construction",
                member.element() != null ? member.element() : targetElement);
        }
        return true;
    }

    /**
     * Whether the {@code canEqual} hook is called and whether it is emitted.
     *
     * <p>Two decisions rather than one, and collapsing them is a silent
     * contract break. An author who writes the hook themselves is opting
     * <b>into</b> the protocol, so their declaration must be reused - called
     * from the generated {@code equals} and not re-emitted. One flag gating both
     * turns that into the opposite: the hook is skipped, the call disappears,
     * the relation collapses to bare {@code instanceof}, and a state-adding
     * subclass then compares asymmetrically with a hash that disagrees. The one
     * act that says "I want this protocol" is the one act that removed it.
     *
     * @param targetElement the annotated type
     * @param target its source tree
     * @param config the resolved attributes
     * @param record whether the target is a record, which is implicitly final
     * @return whether to call the hook, and whether to emit one
     */
    private Hook resolveHook(TypeElement targetElement, JCClassDecl target,
                             EqualityConfig config, boolean record) {
        if (config.identity() != EqualsAndHashCode.Identity.INSTANCE_OF_CANEQUAL) {
            return new Hook(false, false);
        }
        if (authorSignatures(target).contains(CAN_EQUAL + "/1")) return new Hook(true, false);

        boolean isFinal = record || (target.mods.flags & Flags.FINAL) != 0;
        boolean rootLike = SuperResolver.callableSuperclass(targetElement) == null;
        if (isFinal && rootLike) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                EqualityConfig.LABEL + "(identity = INSTANCE_OF_CANEQUAL) on the final type "
                    + targetElement.getSimpleName() + " - no subclass can override the hook, so "
                    + "the bare instanceof relation is emitted instead",
                targetElement);
            return new Hook(false, false);
        }
        return new Hook(true, true);
    }

    /**
     * The two halves of the {@code canEqual} decision.
     *
     * @param call whether the generated {@code equals} consults the hook
     * @param emit whether this pass declares the hook itself
     */
    private record Hook(boolean call, boolean emit) {
    }

    // ------------------------------------------------------------------
    // Emission
    // ------------------------------------------------------------------

    private JCVariableDecl cacheField(GeneratedAnnotations generated) {
        // transient, so the memo is never serialized - a hash is meaningless
        // across JVMs and actively harmful deserialized into a hash table. No
        // volatile and no synchronized either: two threads compute the same
        // value and a 32-bit int write cannot tear.
        JCVariableDecl field = make.VarDef(
            make.Modifiers(Flags.PRIVATE | Flags.TRANSIENT),
            names.fromString(CACHE_FIELD),
            make.TypeIdent(TypeTag.INT),
            null
        );
        AstMarkers.markGenerated(field, generated);
        return field;
    }

    private JCMethodDecl buildEquals(TypeElement targetElement, JCClassDecl target,
                                     EqualityConfig config, boolean callSuper,
                                     boolean canEqual, java.util.List<Resolved> resolved,
                                     ContractAnnotations contracts, GeneratedAnnotations generated) {
        ListBuffer<JCStatement> body = new ListBuffer<>();
        body.append(returnIf(make.Binary(Tag.EQ, ident(PARAM), make.Ident(names._this)), true));

        if (config.identity() == EqualsAndHashCode.Identity.EXACT_CLASS) {
            body.append(returnIf(make.Binary(Tag.OR,
                make.Binary(Tag.EQ, ident(PARAM), nullLit()),
                make.Binary(Tag.NE, getClassOf(make.Ident(names._this)), getClassOf(ident(PARAM)))),
                false));
        } else {
            body.append(returnIf(
                make.Unary(Tag.NOT, make.Parens(make.TypeTest(ident(PARAM), targetRef(targetElement)))),
                false));
        }

        if (canEqual || !resolved.isEmpty()) {
            body.append(make.VarDef(make.Modifiers(Flags.FINAL), names.fromString(OTHER),
                targetRef(targetElement), make.TypeCast(targetRef(targetElement), ident(PARAM))));
        }
        if (canEqual) {
            body.append(returnIf(make.Unary(Tag.NOT, make.Apply(List.nil(),
                make.Select(ident(OTHER), names.fromString(CAN_EQUAL)),
                List.of(make.Ident(names._this)))), false));
        }
        if (callSuper) {
            body.append(returnIf(make.Unary(Tag.NOT, superCall("equals", List.of(ident(PARAM)))),
                false));
        }
        for (Resolved r : resolved) {
            body.append(returnIf(terms.notEqual(r.shape(),
                terms.read(make.Ident(names._this), r.read().name(), r.read().method()),
                terms.read(ident(OTHER), r.read().name(), r.read().method())), false));
        }
        body.append(make.Return(make.Literal(true)));

        JCMethodDecl method = make.MethodDef(
            make.Modifiers(Flags.PUBLIC, contracts.contract("null -> false", true, null)),
            names.fromString("equals"),
            make.TypeIdent(TypeTag.BOOLEAN),
            List.nil(),
            List.of(objectParam(PARAM)),
            List.nil(),
            make.Block(0, body.toList()),
            null
        );
        AstMarkers.markGenerated(method, generated);
        return method;
    }

    private JCMethodDecl buildHashCode(boolean callSuper, boolean cache,
                                       java.util.List<Resolved> resolved,
                                       ContractAnnotations contracts,
                                       GeneratedAnnotations generated) {
        ListBuffer<JCStatement> body = new ListBuffer<>();
        if (cache) {
            body.append(make.VarDef(make.Modifiers(Flags.FINAL), names.fromString(CACHED),
                make.TypeIdent(TypeTag.INT),
                make.Select(make.Ident(names._this), names.fromString(CACHE_FIELD))));
            body.append(make.If(make.Binary(Tag.NE, ident(CACHED), make.Literal(0)),
                make.Return(ident(CACHED)), null));
        }
        body.append(make.VarDef(make.Modifiers(Flags.FINAL), names.fromString(PRIME_NAME),
            make.TypeIdent(TypeTag.INT), make.Literal(PRIME_VALUE)));
        body.append(make.VarDef(make.Modifiers(0), names.fromString(RESULT),
            make.TypeIdent(TypeTag.INT),
            callSuper ? superCall("hashCode", List.nil()) : make.Literal(1)));

        for (Resolved r : resolved) {
            ListBuffer<JCStatement> prelude = new ListBuffer<>();
            JCExpression term = terms.hash(r.shape(),
                () -> terms.read(make.Ident(names._this), r.read().name(), r.read().method()),
                prelude, "$hash$" + r.spec().name());
            body.appendList(prelude.toList());
            body.append(make.Exec(make.Assign(ident(RESULT), make.Binary(Tag.PLUS,
                make.Binary(Tag.MUL, ident(RESULT), ident(PRIME_NAME)), term))));
        }

        if (cache) {
            // Zero is the field's own default and therefore the "not computed
            // yet" sentinel, so a hash that legitimately computes to zero would
            // otherwise recompute on every call forever.
            body.append(make.If(make.Binary(Tag.EQ, ident(RESULT), make.Literal(0)),
                make.Exec(make.Assign(ident(RESULT),
                    types.qualIdent("java.lang.Integer.MIN_VALUE"))),
                null));
            body.append(make.Exec(make.Assign(
                make.Select(make.Ident(names._this), names.fromString(CACHE_FIELD)),
                ident(RESULT))));
        }
        body.append(make.Return(ident(RESULT)));

        JCMethodDecl method = make.MethodDef(
            make.Modifiers(Flags.PUBLIC, cache ? List.nil() : contracts.pure()),
            names.fromString("hashCode"),
            make.TypeIdent(TypeTag.INT),
            List.nil(),
            List.nil(),
            List.nil(),
            make.Block(0, body.toList()),
            null
        );
        AstMarkers.markGenerated(method, generated);
        return method;
    }

    private JCMethodDecl buildCanEqual(TypeElement targetElement, JCClassDecl target,
                                       ContractAnnotations contracts,
                                       GeneratedAnnotations generated) {
        JCMethodDecl method = make.MethodDef(
            make.Modifiers(Flags.PROTECTED, contracts.contract("null -> false", true, null)),
            names.fromString(CAN_EQUAL),
            make.TypeIdent(TypeTag.BOOLEAN),
            List.nil(),
            List.of(objectParam(OTHER)),
            List.nil(),
            make.Block(0, List.of(make.Return(make.TypeTest(ident(OTHER), targetRef(targetElement))))),
            null
        );
        AstMarkers.markGenerated(method, generated);
        return method;
    }

    // ------------------------------------------------------------------
    // Tree primitives
    // ------------------------------------------------------------------

    /**
     * A reference to the target, wildcarded wherever a type parameter is in
     * scope, and qualified through every enclosing type whose parameters the
     * target inherits.
     *
     * <p>{@code equals} takes an {@code Object}, so there is nothing to bind any
     * of those parameters to. Wildcards keep the cast unchecked-free where a raw
     * type would erase every member on the way through, and are what makes the
     * type reifiable enough for an {@code instanceof}.
     *
     * <p><b>The enclosing chain is the part that is easy to miss.</b> An inner
     * class of a generic type declares no parameters of its own, so its bare
     * simple name still denotes {@code Outer<T>.Inner} - which is neither
     * castable from {@code Object} nor usable in an {@code instanceof}, and
     * javac says so on the inner class's own declaration line. Walking out
     * through the non-static members and wildcarding each generic link gives
     * {@code Outer<?>.Inner}, which is both. A {@code static} nested class stops
     * the walk, since it inherits nothing to wildcard.
     *
     * @param targetElement the annotated type
     * @return the reference expression, freshly minted
     */
    private JCExpression targetRef(TypeElement targetElement) {
        java.util.List<TypeElement> chain = new ArrayList<>(2);
        chain.add(targetElement);
        TypeElement current = targetElement;
        while (current.getNestingKind() == NestingKind.MEMBER
            && !current.getModifiers().contains(Modifier.STATIC)
            && current.getEnclosingElement() instanceof TypeElement enclosing) {
            chain.add(0, enclosing);
            current = enclosing;
        }

        JCExpression out = null;
        for (TypeElement link : chain) {
            Name simple = names.fromString(link.getSimpleName().toString());
            out = out == null ? make.Ident(simple) : make.Select(out, simple);
            int arity = link.getTypeParameters().size();
            if (arity > 0) out = make.TypeApply(out, wildcards(arity));
        }
        return out;
    }

    private List<JCExpression> wildcards(int arity) {
        ListBuffer<JCExpression> arguments = new ListBuffer<>();
        for (int i = 0; i < arity; i++) {
            arguments.append(make.Wildcard(make.TypeBoundKind(BoundKind.UNBOUND), null));
        }
        return arguments.toList();
    }

    private JCStatement returnIf(JCExpression condition, boolean value) {
        return make.If(condition, make.Return(make.Literal(value)), null);
    }

    private JCExpression superCall(String method, List<JCExpression> arguments) {
        return make.Apply(List.nil(),
            make.Select(make.Ident(names.fromString("super")), names.fromString(method)),
            arguments);
    }

    private JCExpression getClassOf(JCExpression receiver) {
        return make.Apply(List.nil(),
            make.Select(receiver, names.fromString("getClass")), List.nil());
    }

    private JCVariableDecl objectParam(String name) {
        return make.VarDef(make.Modifiers(Flags.PARAMETER), names.fromString(name),
            types.qualIdent(OBJECT_FQN), null);
    }

    private JCExpression ident(String name) {
        return make.Ident(names.fromString(name));
    }

    private JCExpression nullLit() {
        return make.Literal(TypeTag.BOT, null);
    }

    /**
     * Name-and-arity signatures the author wrote, snapshotted before anything is
     * appended and skipping what this pipeline already generated.
     *
     * <p>A one-argument {@code equals} whose parameter is not {@code Object} is
     * left out, because it is an overload rather than an override. Counting it
     * would refuse to generate the pair on a type whose author added a typed
     * convenience method, and the refusal is an error the author has no way to
     * read as being about that method.
     */
    private static Set<String> authorSignatures(JCClassDecl target) {
        Set<String> out = new HashSet<>();
        for (JCTree def : target.defs) {
            if (!(def instanceof JCMethodDecl method) || AstMarkers.isGenerated(method)) continue;
            if (method.name.contentEquals("equals") && !takesObject(method)) continue;
            out.add(method.name.toString() + "/" + method.params.size());
        }
        return out;
    }

    /** Whether a one-argument method's parameter is spelled {@code Object}. */
    private static boolean takesObject(JCMethodDecl method) {
        if (method.params.size() != 1) return false;
        JCTree type = method.params.head.vartype;
        if (type instanceof JCIdent ident) return ident.name.contentEquals("Object");
        return type instanceof JCFieldAccess select && select.name.contentEquals("Object");
    }

}
