package dev.simplified.equality.inspect;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrefixExpression;
import com.intellij.psi.PsiRecordComponent;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared FQNs, attribute readers and member views for the
 * {@code @EqualsAndHashCode} / {@code @ToString} PSI side, so the two
 * inspections resolve one annotation the same way the one shared selector does
 * in the processor.
 *
 * <p>Every annotation is matched on its written simple name before the
 * reference is resolved. That is a gate rather than an optimisation: resolving
 * a field annotation re-enters every augment provider registered for the class,
 * and the platform answers the cycle by disabling caching and logging an error.
 *
 * <p>Attributes are read as <b>written</b> throughout, against the defaults
 * spelled out here. {@code findAttributeValue} folds in the annotation type's
 * own default and so cannot tell a written value from an unwritten one, which
 * is what every attribute anchor depends on.
 */
public final class WholeObjectConstants {

    public static final @NotNull String EQUALS_AND_HASH_CODE_FQN =
        "dev.simplified.annotations.EqualsAndHashCode";
    public static final @NotNull String EQUALS_EXCLUDE_FQN =
        "dev.simplified.annotations.EqualsExclude";
    public static final @NotNull String EQUALS_INCLUDE_FQN =
        "dev.simplified.annotations.EqualsInclude";

    public static final @NotNull String TO_STRING_FQN = "dev.simplified.annotations.ToString";
    public static final @NotNull String TO_STRING_EXCLUDE_FQN =
        "dev.simplified.annotations.ToStringExclude";
    public static final @NotNull String TO_STRING_INCLUDE_FQN =
        "dev.simplified.annotations.ToStringInclude";

    public static final @NotNull String IDENTITY_FQN =
        "dev.simplified.annotations.EqualsAndHashCode.Identity";
    public static final @NotNull String CLASS_BUILDER_FQN =
        "dev.simplified.annotations.ClassBuilder";
    public static final @NotNull String BUILDER_IGNORE_FQN =
        "dev.simplified.annotations.BuilderIgnore";
    public static final @NotNull String LAZY_FQN = "dev.simplified.annotations.Lazy";

    public static final @NotNull String ATTR_IDENTITY = "identity";
    public static final @NotNull String ATTR_CALL_SUPER = "callSuper";
    public static final @NotNull String ATTR_OF = "of";
    public static final @NotNull String ATTR_EXCLUDE = "exclude";
    public static final @NotNull String ATTR_CACHE_HASH_CODE = "cacheHashCode";
    public static final @NotNull String ATTR_USE_ACCESSORS = "useAccessors";
    public static final @NotNull String ATTR_STYLE = "style";
    public static final @NotNull String ATTR_INCLUDE_FIELD_NAMES = "includeFieldNames";

    /** The printed name {@code @ToStringInclude} may write in place of the member's own. */
    public static final @NotNull String ATTR_NAME = "name";

    /** The print-order weight {@code @ToStringInclude} may write, higher first. */
    public static final @NotNull String ATTR_RANK = "rank";

    /** The identity relation a bare {@code @EqualsAndHashCode} asks for. */
    public static final @NotNull String EXACT_CLASS = "EXACT_CLASS";

    /** The relation whose cooperation hook a subclass has to keep overriding. */
    public static final @NotNull String INSTANCE_OF_CANEQUAL = "INSTANCE_OF_CANEQUAL";

    /** That hook, the one name either feature synthesises. */
    public static final @NotNull String CAN_EQUAL = "canEqual";

    private static final @NotNull String OBJECT_FQN = "java.lang.Object";
    private static final @NotNull String RECORD_FQN = "java.lang.Record";

    /**
     * The most supertypes a walk here follows.
     *
     * <p>Deeper than any hierarchy a human writes and shallower than a loop worth
     * waiting on, so the bound only ever fires on source that is already wrong.
     */
    private static final int MAX_CHAIN = 64;

    private WholeObjectConstants() {
    }

    /**
     * One annotation's selection rules, mirroring what the processor hands its
     * shared member selector.
     *
     * <p>{@code honourRank} is the second of the two inputs the annotations
     * differ on, and the asymmetry is deliberate rather than an omission.
     * {@code @ToStringInclude} carries a printed name and a print-order weight
     * because both are cosmetic and both are visible in the output;
     * {@code @EqualsInclude} carries neither, since the same attribute on the
     * equality pair would make an emitted hash depend on an ordering rule
     * nothing in the source shows.
     *
     * @param label the annotation as a diagnostic spells it
     * @param annotationFqn the type-level annotation
     * @param excludeFqn the marker removing a member
     * @param includeFqn the marker adding one back
     * @param keepTransient whether a {@code transient} field stays selected
     * @param honourRank whether the include marker carries a printed name and a sort key
     */
    public record Policy(@NotNull String label, @NotNull String annotationFqn,
                         @NotNull String excludeFqn, @NotNull String includeFqn,
                         boolean keepTransient, boolean honourRank) {
    }

    /** {@code @EqualsAndHashCode}'s rules, which drop {@code transient} state. */
    public static final @NotNull Policy EQUALITY_POLICY = new Policy("@EqualsAndHashCode",
        EQUALS_AND_HASH_CODE_FQN, EQUALS_EXCLUDE_FQN, EQUALS_INCLUDE_FQN, false, false);

    /** {@code @ToString}'s rules, which keep it - a field left out of serialization is still state a dump wants. */
    public static final @NotNull Policy TO_STRING_POLICY = new Policy("@ToString",
        TO_STRING_FQN, TO_STRING_EXCLUDE_FQN, TO_STRING_INCLUDE_FQN, true, true);

    /**
     * A member the resolution asks a supertype about.
     *
     * <p>The parameter type is carried alongside the count because a one-argument
     * {@code equals} is not necessarily <b>the</b> {@code equals}: a typed
     * convenience overload {@code equals(Vec)} overrides nothing and supplies no
     * equality relation, and reading it as the override is what turns a legal
     * value type into a rejected one.
     *
     * @param name the member's name
     * @param arity how many parameters it takes
     * @param parameterType the sole parameter's type, or {@code null} when the
     *     count settles the match on its own
     */
    public record Signature(@NotNull String name, int arity, @Nullable String parameterType) {

        /** {@code equals(Object)}. */
        public static final Signature EQUALS = new Signature("equals", 1, OBJECT_FQN);

        /** {@code hashCode()}. */
        public static final Signature HASH_CODE = new Signature("hashCode", 0, null);

        /** {@code toString()}. */
        public static final Signature TO_STRING = new Signature("toString", 0, null);

        /** {@code canEqual(Object)}. */
        public static final Signature CAN_EQUAL_HOOK = new Signature(CAN_EQUAL, 1, OBJECT_FQN);

    }

    /**
     * One member a generated whole-object method reads.
     *
     * @param name the member's own name, which is what {@code of} and {@code exclude} match
     * @param label the name printed for it, which the include marker may rewrite
     * @param anchor the identifier a problem about it is registered on
     * @param type the declared type the emission row is chosen from
     * @param mutable whether the value can change after construction
     * @param rank sort key, higher first, with declaration order inside one rank
     */
    public record Selected(@NotNull String name, @NotNull String label, @Nullable PsiElement anchor,
                           @Nullable PsiType type, boolean mutable, int rank) {
    }

    // ------------------------------------------------------------------
    // Annotation readers
    // ------------------------------------------------------------------

    /**
     * Finds an annotation written on a member or a type.
     *
     * <p>The written simple name is compared before the reference is resolved,
     * so an unrelated annotation costs one string comparison rather than a
     * resolve that would re-enter the augment providers.
     *
     * @param owner the element to read, or {@code null}
     * @param fqn the annotation's fully-qualified name
     * @return the annotation, or {@code null} when it is not written
     */
    public static @Nullable PsiAnnotation find(@Nullable PsiModifierListOwner owner,
                                               @NotNull String fqn) {
        if (owner == null) return null;
        PsiModifierList modifiers = owner.getModifierList();
        if (modifiers == null) return null;
        String simpleName = fqn.substring(fqn.lastIndexOf('.') + 1);
        for (PsiAnnotation annotation : modifiers.getAnnotations()) {
            PsiJavaCodeReferenceElement reference = annotation.getNameReferenceElement();
            if (reference == null) continue;
            if (!simpleName.equals(reference.getReferenceName())) continue;
            if (fqn.equals(annotation.getQualifiedName())) return annotation;
        }
        return null;
    }

    /** Whether the annotation is written on the element at all. */
    public static boolean has(@Nullable PsiModifierListOwner owner, @NotNull String fqn) {
        return find(owner, fqn) != null;
    }

    /**
     * The value written for an attribute, which is where a problem about that
     * attribute is registered.
     *
     * @param annotation the annotation to read
     * @param attribute the attribute name
     * @return the written value, or {@code null} when the attribute is defaulted
     */
    public static @Nullable PsiAnnotationMemberValue written(@NotNull PsiAnnotation annotation,
                                                             @NotNull String attribute) {
        return annotation.findDeclaredAttributeValue(attribute);
    }

    /**
     * Reads an enum-constant attribute by its trailing reference name, so
     * {@code EXACT_CLASS}, {@code Identity.EXACT_CLASS} and
     * {@code EqualsAndHashCode.Identity.EXACT_CLASS} all read the same.
     *
     * @param annotation the annotation to read
     * @param attribute the attribute name
     * @param fallback the annotation's own default
     * @return the constant name, or the fallback when the attribute is unwritten
     */
    public static @NotNull String enumAttr(@NotNull PsiAnnotation annotation,
                                           @NotNull String attribute, @NotNull String fallback) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attribute);
        if (value instanceof PsiReferenceExpression reference) {
            String name = reference.getReferenceName();
            if (name != null) return name;
        }
        return fallback;
    }

    /**
     * Reads a boolean attribute as written.
     *
     * @param annotation the annotation to read
     * @param attribute the attribute name
     * @param fallback the annotation's own default
     * @return the written value, or the fallback
     */
    public static boolean booleanAttr(@NotNull PsiAnnotation annotation, @NotNull String attribute,
                                      boolean fallback) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attribute);
        if (value instanceof PsiLiteralExpression literal
            && literal.getValue() instanceof Boolean written) return written;
        return fallback;
    }

    /**
     * Reads an integer attribute as written, sign included.
     *
     * <p>The sign is part of reading the literal rather than a step towards
     * evaluating an expression. There is no negative integer literal in the
     * language: {@code rank = -1} always parses as a unary minus over the
     * literal {@code 1}, so a reader accepting only the literal resolves every
     * negative write to the attribute's default - and a negative rank is
     * precisely how a member asks to be sorted last, which would put it where
     * nothing asked for it. A unary plus is accepted with it because it is the
     * same node for no extra cost.
     *
     * @param annotation the annotation to read
     * @param attribute the attribute name
     * @param fallback the annotation's own default
     * @return the written value, or the fallback
     */
    public static int intAttr(@NotNull PsiAnnotation annotation, @NotNull String attribute,
                              int fallback) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attribute);
        PsiElement operand = value;
        int sign = 1;
        if (value instanceof PsiPrefixExpression prefix) {
            IElementType operator = prefix.getOperationTokenType();
            if (JavaTokenType.MINUS.equals(operator)) sign = -1;
            else if (!JavaTokenType.PLUS.equals(operator)) return fallback;
            operand = prefix.getOperand();
        }
        if (operand instanceof PsiLiteralExpression literal
            && literal.getValue() instanceof Integer written) return sign * written;
        return fallback;
    }

    /**
     * The entries of a string-array attribute, kept as values rather than
     * strings so a problem lands on the one entry it is about.
     *
     * <p>A bare literal is accepted as the single-element form, which is how the
     * language lets {@code of = "label"} stand for {@code of = {"label"}}.
     *
     * @param annotation the annotation to read
     * @param attribute the attribute name
     * @return the written entries, empty when the attribute is unwritten
     */
    public static @NotNull List<PsiAnnotationMemberValue> entries(@NotNull PsiAnnotation annotation,
                                                                  @NotNull String attribute) {
        List<PsiAnnotationMemberValue> out = new ArrayList<>();
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attribute);
        if (value instanceof PsiArrayInitializerMemberValue array) {
            for (PsiAnnotationMemberValue entry : array.getInitializers()) {
                if (stringValue(entry) != null) out.add(entry);
            }
        } else if (value != null && stringValue(value) != null) {
            out.add(value);
        }
        return out;
    }

    /** The string a written attribute value holds, or {@code null} when it is not a string literal. */
    public static @Nullable String stringValue(@NotNull PsiAnnotationMemberValue value) {
        if (value instanceof PsiLiteralExpression literal
            && literal.getValue() instanceof String written) return written;
        return null;
    }

    /** The names an array attribute lists, in written order. */
    public static @NotNull List<String> names(@NotNull PsiAnnotation annotation,
                                              @NotNull String attribute) {
        List<String> out = new ArrayList<>();
        for (PsiAnnotationMemberValue entry : entries(annotation, attribute)) {
            out.add(stringValue(entry));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Member views
    // ------------------------------------------------------------------

    /**
     * Fields the class itself declares.
     *
     * <p>{@code getOwnFields()} rather than {@code getFields()}: the latter is
     * augment-aware and re-enters every provider registered for the class.
     *
     * @param target the class to read
     * @return the declared fields, in declaration order
     */
    public static @NotNull List<PsiField> ownFields(@NotNull PsiClass target) {
        return target instanceof PsiExtensibleClass extensible
            ? extensible.getOwnFields()
            : List.of(target.getFields());
    }

    /**
     * Methods and constructors the class itself declares.
     *
     * @param target the class to read
     * @return the declared methods, in declaration order
     */
    public static @NotNull List<PsiMethod> ownMethods(@NotNull PsiClass target) {
        return target instanceof PsiExtensibleClass extensible
            ? extensible.getOwnMethods()
            : List.of(target.getMethods());
    }

    /**
     * Fields and methods the class itself declares, in one declaration-order
     * walk.
     *
     * <p>Merged rather than read as two lists because the processor makes a
     * single pass over the target's enclosed elements. An include-marked method
     * declared between two fields is emitted between them, so a walk taking
     * every field before every method describes it in a position it never
     * occupies.
     *
     * @param target the class to read
     * @return the declared fields and methods, in declaration order
     */
    private static @NotNull List<PsiMember> ownMembers(@NotNull PsiClass target) {
        List<PsiMember> out = new ArrayList<>();
        out.addAll(ownFields(target));
        out.addAll(ownMethods(target));
        // Stable, so a class with no source behind it - where every member
        // reports the same offset - keeps the order the two lists arrived in
        // rather than being shuffled into an arbitrary one.
        out.sort(Comparator.comparingInt(WholeObjectConstants::declarationOffset));
        return out;
    }

    private static int declarationOffset(@NotNull PsiElement member) {
        TextRange range = member.getTextRange();
        return range == null ? 0 : range.getStartOffset();
    }

    /** The canonical accessor names of a record's components, empty for anything else. */
    private static @NotNull Set<String> recordComponentNames(@NotNull PsiClass target) {
        if (!target.isRecord()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (PsiRecordComponent component : target.getRecordComponents()) {
            String name = component.getName();
            if (name != null) out.add(name);
        }
        return out;
    }

    /**
     * The members one annotation reads, before {@code of} or {@code exclude}
     * narrows them.
     *
     * <p>Instance state and include-marked methods in one declaration-order
     * walk. A record is walked through its components rather than its fields,
     * which is where the PSI keeps a component's own annotations, and its
     * components lead because they are declared ahead of the body.
     *
     * @param target the annotated type
     * @param policy the annotation's selection rules
     * @return the candidate members, in emission order
     */
    public static @NotNull List<Selected> candidates(@NotNull PsiClass target,
                                                     @NotNull Policy policy) {
        List<Selected> out = new ArrayList<>();
        boolean record = target.isRecord();
        Set<String> components = recordComponentNames(target);
        if (record) {
            for (PsiRecordComponent component : target.getRecordComponents()) {
                PsiAnnotation include = find(component, policy.includeFqn());
                if (skipped(component, component.getName(), policy, false, include)) continue;
                out.add(new Selected(component.getName(),
                    label(component.getName(), include, policy), component.getNameIdentifier(),
                    component.getType(), false, rank(include, policy)));
            }
        }
        for (PsiMember member : ownMembers(target)) {
            if (member instanceof PsiField field) {
                // A record's state is its components, read above - a field it
                // declares in its body can only be static.
                if (record) continue;
                // An enum's constants are fields of the enum type as far as the
                // PSI is concerned, and are not per-instance state.
                if (field instanceof PsiEnumConstant) continue;
                if (field.hasModifierProperty(PsiModifier.STATIC)) continue;
                boolean transientField = field.hasModifierProperty(PsiModifier.TRANSIENT);
                PsiAnnotation include = find(field, policy.includeFqn());
                if (skipped(field, field.getName(), policy, transientField, include)) continue;
                out.add(new Selected(field.getName(), label(field.getName(), include, policy),
                    field.getNameIdentifier(), field.getType(),
                    !field.hasModifierProperty(PsiModifier.FINAL), rank(include, policy)));
            } else if (member instanceof PsiMethod method) {
                // A record component's annotations are propagated onto both the
                // backing field and the accessor whenever the target allows
                // both - which the include marker does. Reading an explicitly
                // declared canonical accessor as a second member would compare
                // and print the one component twice. The zero-arg gate spares an
                // unrelated overload of the component's name, which is not the
                // accessor and is nothing to do with the component.
                if (method.getParameterList().isEmpty()
                    && components.contains(method.getName())) {
                    continue;
                }
                if (!includable(method)) continue;
                PsiAnnotation include = find(method, policy.includeFqn());
                if (include == null) continue;
                out.add(new Selected(method.getName(), label(method.getName(), include, policy),
                    method.getNameIdentifier(), method.getReturnType(), true,
                    rank(include, policy)));
            }
        }
        return out;
    }

    /**
     * Whether a field or component drops out before the narrowing attributes are
     * even read.
     *
     * @param owner the field or record component
     * @param name its name
     * @param policy the annotation's selection rules
     * @param isTransient whether it is declared {@code transient}
     * @param include the include marker written on it, or {@code null}
     * @return whether the member is skipped
     */
    private static boolean skipped(@NotNull PsiModifierListOwner owner, @Nullable String name,
                                   @NotNull Policy policy, boolean isTransient,
                                   @Nullable PsiAnnotation include) {
        // A synthesised slot - an outer-instance link, a switch map, the hash
        // memo this feature emits itself. The test is on a '$' anywhere rather
        // than only in front, since the compiler's own names put it in the
        // middle.
        if (name == null || name.indexOf('$') >= 0) return true;
        if (has(owner, policy.excludeFqn())) return true;
        if (has(owner, LAZY_FQN)) return true;
        return isTransient && !policy.keepTransient() && include == null;
    }

    /** The printed name, which only an include marker that carries one can rewrite. */
    private static @NotNull String label(@Nullable String name, @Nullable PsiAnnotation include,
                                         @NotNull Policy policy) {
        String own = name == null ? "" : name;
        if (include == null || !policy.honourRank()) return own;
        PsiAnnotationMemberValue value = written(include, ATTR_NAME);
        String rewritten = value == null ? null : stringValue(value);
        return rewritten == null || rewritten.isEmpty() ? own : rewritten;
    }

    /** The print-order weight, zero wherever the include marker carries none. */
    private static int rank(@Nullable PsiAnnotation include, @NotNull Policy policy) {
        if (include == null || !policy.honourRank()) return 0;
        return intAttr(include, ATTR_RANK, 0);
    }

    /** Whether a method is the shape the include marker can add - zero-arg, value-returning, per-instance. */
    public static boolean includable(@NotNull PsiMethod method) {
        if (method.isConstructor()) return false;
        if (method.hasModifierProperty(PsiModifier.STATIC)) return false;
        if (!method.getParameterList().isEmpty()) return false;
        PsiType returnType = method.getReturnType();
        return returnType != null && !PsiTypes.voidType().equals(returnType);
    }

    /**
     * Applies {@code of} or {@code exclude} to the candidates.
     *
     * @param candidates the members the selection reaches
     * @param of names to keep to the exclusion of every other
     * @param exclude names to drop
     * @return the members the generated method actually reads
     */
    public static @NotNull List<Selected> narrow(@NotNull List<Selected> candidates,
                                                 @NotNull Collection<String> of,
                                                 @NotNull Collection<String> exclude) {
        List<Selected> out = new ArrayList<>(candidates.size());
        for (Selected member : candidates) {
            if (!of.isEmpty() && !of.contains(member.name())) continue;
            if (of.isEmpty() && exclude.contains(member.name())) continue;
            out.add(member);
        }
        return out;
    }

    /**
     * The members the generated method reads, narrowed and in emission order.
     *
     * <p>The whole resolution in one call, because the two things reading it -
     * the reports and the gutter describing the same annotation - drifting
     * apart is a description that is confidently wrong and that nothing else
     * contradicts.
     *
     * <p>The rank sort is applied where the processor applies it, after the
     * narrowing and never before: a member the attributes remove cannot pull a
     * later one forward. It is a stable sort on descending rank, so members
     * sharing a rank keep declaration order.
     *
     * @param target the annotated type
     * @param annotation the type-level annotation
     * @param policy the annotation's selection rules
     * @return the members the generated method reads, in the order it reads them
     */
    public static @NotNull List<Selected> selected(@NotNull PsiClass target,
                                                   @NotNull PsiAnnotation annotation,
                                                   @NotNull Policy policy) {
        List<Selected> out = narrow(candidates(target, policy), names(annotation, ATTR_OF),
            names(annotation, ATTR_EXCLUDE));
        if (policy.honourRank()) out.sort((a, b) -> Integer.compare(b.rank(), a.rank()));
        return out;
    }

    /**
     * Whether the generated member actually reads a named member of the type.
     *
     * <p>The whole resolution rather than any one of its steps, because a member
     * leaves the selection in six different ways - {@code static},
     * {@code transient} under a policy that drops it, a {@code $} in the name,
     * {@code @Lazy}, the exclude marker, and either narrowing attribute - and a
     * report about "a member the relation still reads" is wrong on every one of
     * them.
     *
     * @param owner the annotated type
     * @param annotation the type-level annotation
     * @param policy the annotation's selection rules
     * @param name the member to look for
     * @return whether the member survives to the generated member
     */
    public static boolean reaches(@NotNull PsiClass owner, @NotNull PsiAnnotation annotation,
                                  @NotNull Policy policy, @NotNull String name) {
        for (Selected member : selected(owner, annotation, policy)) {
            if (name.equals(member.name())) return true;
        }
        return false;
    }

    /**
     * Whether a narrowing attribute already names a member.
     *
     * <p>What a fix writing the exclude marker has to ask first. A name listed in
     * {@code of} or {@code exclude} and a marker on the member it names are two
     * statements about one member, and the selection reports a name it can no
     * longer reach as a hard error - so the marker would trade a prompt for a
     * failed build.
     *
     * @param annotation the type-level annotation
     * @param name the member to look for
     * @return whether either narrowing attribute lists it
     */
    public static boolean namedByNarrowing(@NotNull PsiAnnotation annotation,
                                           @NotNull String name) {
        return names(annotation, ATTR_OF).contains(name)
            || names(annotation, ATTR_EXCLUDE).contains(name);
    }

    // ------------------------------------------------------------------
    // Supertypes
    // ------------------------------------------------------------------

    /**
     * The direct superclass, or {@code null} when there is none worth calling.
     *
     * <p>{@code java.lang.Record} counts as none alongside
     * {@code java.lang.Object}: it declares the equality pair and
     * {@code toString} abstract, so a {@code super} call to any of them is
     * rejected outright.
     *
     * @param target the type to walk up from
     * @return the superclass a generated member could call, or {@code null}
     */
    public static @Nullable PsiClass callableSuperclass(@NotNull PsiClass target) {
        PsiClass superclass = target.getSuperClass();
        if (superclass == null || superclass.isInterface()) return null;
        String fqn = superclass.getQualifiedName();
        if (OBJECT_FQN.equals(fqn) || RECORD_FQN.equals(fqn)) return null;
        return superclass;
    }

    /**
     * A type and the superclasses above it, in order, guarded against a cycle.
     *
     * <p>Every walk up a hierarchy on this side goes through here, and both
     * guards are load bearing rather than defensive. {@code getSuperClass()}
     * resolves an {@code extends} reference with no cycle detection of its own,
     * and a cycle is the normal state of a file being typed - {@code class A
     * extends B} beside {@code class B extends A} is one rename away, and
     * {@code class A extends A} reproduces it in a single line. An unguarded loop
     * over it never returns, on the daemon thread, which the user sees as a
     * frozen IDE rather than as a wrong answer. The depth bound backs the visited
     * set up rather than duplicating it: PSI equality is instance identity, so a
     * resolve handing back a fresh instance would slip past the set alone.
     *
     * <p>The cancellation check is the other half - a highlighting pass is
     * abandoned whenever the file changes underneath it, and a loop that never
     * yields cannot be.
     *
     * @param start the type to begin at, which is itself the first entry
     * @param callableOnly whether to stop where a generated member would, at
     *     {@code Object}, {@code Record} or an interface
     * @return the chain, beginning with the start
     */
    public static @NotNull List<PsiClass> chainFrom(@NotNull PsiClass start, boolean callableOnly) {
        List<PsiClass> out = new ArrayList<>();
        Set<PsiClass> seen = new HashSet<>();
        PsiClass current = start;
        while (current != null && out.size() < MAX_CHAIN && seen.add(current)) {
            ProgressManager.checkCanceled();
            out.add(current);
            current = callableOnly ? callableSuperclass(current) : current.getSuperClass();
        }
        return out;
    }

    /**
     * The chain above a type, which is what a check about its supertypes reads.
     *
     * @param target the type to walk up from, itself excluded
     * @return the callable supertypes, nearest first
     */
    public static @NotNull List<PsiClass> callableSupertypes(@NotNull PsiClass target) {
        PsiClass first = callableSuperclass(target);
        return first == null ? List.of() : chainFrom(first, true);
    }

    /**
     * The method a class declares under a signature, or {@code null}.
     *
     * <p>An overload of the same arity is not a match: the signature's parameter
     * type is compared where it names one, so a hand-written {@code equals(Vec)}
     * beside no {@code equals(Object)} reads as the type declaring no
     * {@code equals} at all - which is what javac sees, and reporting otherwise
     * would red an annotation the build accepts.
     *
     * @param target the class to read
     * @param signature the name, arity and parameter type to match
     * @return the declared method, or {@code null}
     */
    public static @Nullable PsiMethod declares(@NotNull PsiClass target,
                                               @NotNull Signature signature) {
        for (PsiMethod method : ownMethods(target)) {
            if (method.isConstructor()) continue;
            if (!signature.name().equals(method.getName())) continue;
            PsiParameter[] parameters = method.getParameterList().getParameters();
            if (parameters.length != signature.arity()) continue;
            if (signature.parameterType() == null
                || parameters[0].getType().equalsToText(signature.parameterType())) {
                return method;
            }
        }
        return null;
    }

    /**
     * Any method of a name and parameter count the class declares, whatever its
     * parameter is spelled.
     *
     * <p>The counterpart to {@link #declares}, for the one question that is about
     * the overload rather than the override: an author-written hook is reused by
     * the generated {@code equals} on its name and arity alone, so a hook of the
     * wrong shape has to be found before it can be reported.
     *
     * @param target the class to read
     * @param name the method name
     * @param arity how many parameters it takes
     * @return the declared method, or {@code null}
     */
    public static @Nullable PsiMethod declaredOverload(@NotNull PsiClass target,
                                                       @NotNull String name, int arity) {
        for (PsiMethod method : ownMethods(target)) {
            if (method.isConstructor()) continue;
            if (!name.equals(method.getName())) continue;
            if (method.getParameterList().getParametersCount() == arity) return method;
        }
        return null;
    }

    /**
     * The nearest supertype declaring a member {@code final}, which no generated
     * override can compile past.
     *
     * @param target the annotated type
     * @param signature the member about to be generated
     * @return the offending supertype, or {@code null} when there is none
     */
    public static @Nullable PsiClass finalSupertypeMember(@NotNull PsiClass target,
                                                          @NotNull Signature signature) {
        for (PsiClass current : callableSupertypes(target)) {
            PsiMethod declared = declares(current, signature);
            if (declared != null && declared.hasModifierProperty(PsiModifier.FINAL)) return current;
        }
        return null;
    }

    /**
     * Resolves {@code callSuper} against what the superclass actually supplies.
     *
     * <p>The annotation mirror is read first and the member scan only after, and
     * the scan requires <b>every</b> named member rather than any of them: a
     * superclass overriding {@code equals} without {@code hashCode} is already
     * inconsistent, so finding exactly one settles nothing. That stand-off is
     * reported through {@link #callSuperUnresolvable} rather than folded into the
     * {@code false} returned here, which would say the resolution succeeded.
     *
     * @param target the annotated type
     * @param written the attribute as the author wrote it
     * @param annotationFqn this annotation, looked for on the superclass
     * @param members every member the superclass must supply for a positive answer
     * @return whether the generated members call the superclass's own
     */
    public static boolean callSuper(@NotNull PsiClass target, @NotNull String written,
                                    @NotNull String annotationFqn,
                                    @NotNull Signature... members) {
        PsiClass superclass = callableSuperclass(target);
        if ("NO".equals(written)) return false;
        if (superclass == null) return false;
        if ("YES".equals(written)) return true;
        if (has(superclass, annotationFqn)) return true;
        return concreteCount(superclass, members) == members.length;
    }

    /**
     * Whether {@code AUTO} has no answer at all, because the superclass supplies
     * some of the named members and not the rest.
     *
     * <p>The processor treats that as an error rather than resolving it either
     * way, and this is what keeps the IDE from being quieter than the build on
     * source that does not compile. The boolean the resolution returns cannot
     * carry it: the same {@code false} means "resolved to NO" and "could not be
     * resolved", and only one of them is a member the author has to write.
     *
     * @param target the annotated type
     * @param written the attribute as the author wrote it
     * @param annotationFqn this annotation, looked for on the superclass
     * @param members every member the superclass must supply for a positive answer
     * @return whether the resolution is a reportable stand-off
     */
    public static boolean callSuperUnresolvable(@NotNull PsiClass target, @NotNull String written,
                                                @NotNull String annotationFqn,
                                                @NotNull Signature... members) {
        if ("NO".equals(written) || "YES".equals(written)) return false;
        PsiClass superclass = callableSuperclass(target);
        if (superclass == null) return false;
        if (has(superclass, annotationFqn)) return false;
        int found = concreteCount(superclass, members);
        return found > 0 && found < members.length;
    }

    private static int concreteCount(@NotNull PsiClass superclass, @NotNull Signature[] members) {
        int found = 0;
        for (Signature member : members) {
            if (declaresConcrete(superclass, member)) found++;
        }
        return found;
    }

    private static boolean declaresConcrete(@NotNull PsiClass start, @NotNull Signature signature) {
        for (PsiClass current : chainFrom(start, true)) {
            PsiMethod declared = declares(current, signature);
            if (declared != null) return !declared.hasModifierProperty(PsiModifier.ABSTRACT);
        }
        return false;
    }

}
