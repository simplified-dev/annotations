package dev.simplified.equality.inspect;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiNameValuePair;
import com.intellij.psi.PsiRecordComponent;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import dev.simplified.equality.inspect.WholeObjectConstants.Policy;
import dev.simplified.equality.inspect.WholeObjectConstants.Selected;
import dev.simplified.equality.inspect.WholeObjectConstants.Signature;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Reports {@code @EqualsAndHashCode} misuse while the file is being edited, and
 * the two identity failures that reach past what any emission can decide.
 *
 * <p>Most checks restate a diagnostic the processor already emits, so their
 * value is timing and position rather than the message. Three do not, and they
 * are why the inspection earns its keep: the recommendation to switch a
 * proxy-backed type off exact-class identity, a subclass overriding
 * {@code equals} without the cooperation hook, and the container-of-array
 * warning, which is the one limitation of the feature a reader cannot infer
 * from the annotation and the one most likely to be caught while the field is
 * still being typed.
 */
public final class EqualsAndHashCodeInspection extends LocalInspectionTool {

    private static final @NotNull String FQN = WholeObjectConstants.EQUALS_AND_HASH_CODE_FQN;
    private static final @NotNull String EXCLUDE_FQN = WholeObjectConstants.EQUALS_EXCLUDE_FQN;
    private static final @NotNull String INCLUDE_FQN = WholeObjectConstants.EQUALS_INCLUDE_FQN;
    private static final @NotNull Policy POLICY = WholeObjectConstants.EQUALITY_POLICY;
    private static final @NotNull String LABEL = POLICY.label();

    private static final @NotNull String COLLECTION_FQN = "java.util.Collection";
    private static final @NotNull String OBJECT_FQN = "java.lang.Object";

    /** The JPA type marker, in both namespaces the ecosystem spells it in. */
    private static final @NotNull String[] ENTITY_FQNS = {
        "jakarta.persistence.Entity", "javax.persistence.Entity"
    };

    /** Associations JPA fetches lazily unless the attribute says otherwise. */
    private static final @NotNull String[] LAZY_BY_DEFAULT = {
        "jakarta.persistence.OneToMany", "javax.persistence.OneToMany",
        "jakarta.persistence.ManyToMany", "javax.persistence.ManyToMany",
        "jakarta.persistence.ElementCollection", "javax.persistence.ElementCollection"
    };

    /** Associations that are eager unless the attribute says otherwise. */
    private static final @NotNull String[] EAGER_BY_DEFAULT = {
        "jakarta.persistence.ManyToOne", "javax.persistence.ManyToOne",
        "jakarta.persistence.OneToOne", "javax.persistence.OneToOne",
        "jakarta.persistence.Basic", "javax.persistence.Basic"
    };

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,
                                                   boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitClass(@NotNull PsiClass target) {
                super.visitClass(target);
                PsiAnnotation annotation = WholeObjectConstants.find(target, FQN);
                if (annotation == null) {
                    checkSubclassHook(holder, target);
                    return;
                }
                if (checkTargetKind(holder, target, annotation)) return;
                if (checkCollision(holder, target, annotation)) return;
                if (checkFinalInSupertype(holder, target, annotation)) return;
                if (checkIdentityAgreement(holder, target, annotation)) return;

                checkCallSuper(holder, target, annotation);
                checkCallSuperResolvable(holder, target, annotation);
                List<Selected> members = checkNarrowing(holder, target, annotation);
                checkCache(holder, target, annotation, members);
                checkMemberTypes(holder, annotation, members);
                checkIdentityRecommendation(holder, target, annotation);
                checkDegradedHook(holder, target, annotation);
                checkHookShape(holder, target, annotation);
            }

            @Override
            public void visitField(@NotNull PsiField field) {
                super.visitField(field);
                if (field instanceof PsiEnumConstant) return;
                checkMarkers(holder, field, field.getName(), field.getContainingClass());
            }

            @Override
            public void visitRecordComponent(@NotNull PsiRecordComponent component) {
                super.visitRecordComponent(component);
                checkMarkers(holder, component, component.getName(),
                    component.getContainingClass());
            }

            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                super.visitMethod(method);
                checkIncludedMethod(holder, method);
            }
        };
    }

    // ------------------------------------------------------------------
    // Refusals the processor also makes
    // ------------------------------------------------------------------

    /**
     * Rejects the target kinds that carry no instance state to compare.
     *
     * <p>An interface carrying {@code @ClassBuilder} is deliberately silent: the
     * concrete class the builder emits for it is where the pair lands, and
     * nothing else on this type is worth reading.
     *
     * @param holder the problems holder
     * @param target the annotated type
     * @param annotation the {@code @EqualsAndHashCode} annotation
     * @return whether nothing further is worth reporting
     */
    private static boolean checkTargetKind(@NotNull ProblemsHolder holder, @NotNull PsiClass target,
                                           @NotNull PsiAnnotation annotation) {
        if (target.isAnnotationType()) {
            holder.registerProblem(annotation,
                LABEL + " on the annotation type " + target.getName()
                    + " - only classes and records carry the instance state this reads",
                ProblemHighlightType.GENERIC_ERROR);
            return true;
        }
        if (target.isInterface()) {
            if (WholeObjectConstants.has(target, WholeObjectConstants.CLASS_BUILDER_FQN)) return true;
            holder.registerProblem(annotation,
                LABEL + " on the interface " + target.getName() + ", which declares no state to "
                    + "compare and no body to generate into. Write it on the implementing type, or "
                    + "add @ClassBuilder so an implementation exists for it to reach",
                ProblemHighlightType.GENERIC_ERROR);
            return true;
        }
        if (target.isEnum()) {
            holder.registerProblem(annotation,
                LABEL + " on the enum " + target.getName() + " - an enum constant is already "
                    + "unique, and Enum's own members are final or name it",
                ProblemHighlightType.GENERIC_ERROR);
            return true;
        }
        return false;
    }

    /**
     * Reports a pair the author already wrote.
     *
     * <p>An error rather than the silent skip {@code @ClassBuilder} and
     * {@code @Getter} both perform: those decline to supply a member the author
     * can still see and call, where an annotation asking for an equality
     * relation and then supplying none leaves the type with a relation nobody
     * wrote down.
     */
    private static boolean checkCollision(@NotNull ProblemsHolder holder, @NotNull PsiClass target,
                                          @NotNull PsiAnnotation annotation) {
        boolean equals = WholeObjectConstants.declares(target, Signature.EQUALS) != null;
        boolean hashCode = WholeObjectConstants.declares(target, Signature.HASH_CODE) != null;
        if (!equals && !hashCode) return false;
        String written = equals && hashCode ? "equals and hashCode" : equals ? "equals" : "hashCode";
        holder.registerProblem(annotation,
            LABEL + " on " + target.getName() + ", which already declares " + written
                + " - generating the pair would leave the type with an equality relation that is "
                + "half written down and half not. Remove the annotation, or remove the members it "
                + "is meant to supply",
            ProblemHighlightType.GENERIC_ERROR);
        return true;
    }

    /** Reports a supertype declaring either member {@code final}, which no override can compile past. */
    private static boolean checkFinalInSupertype(@NotNull ProblemsHolder holder,
                                                 @NotNull PsiClass target,
                                                 @NotNull PsiAnnotation annotation) {
        for (Signature member : new Signature[]{Signature.EQUALS, Signature.HASH_CODE}) {
            PsiClass owner = WholeObjectConstants.finalSupertypeMember(target, member);
            if (owner == null) continue;
            holder.registerProblem(annotation,
                LABEL + " cannot generate " + member.name() + " on " + target.getName() + " - "
                    + owner.getQualifiedName() + " declares it final",
                ProblemHighlightType.GENERIC_ERROR);
            return true;
        }
        return false;
    }

    /**
     * Reports an annotated supertype asking for a different identity relation,
     * which is an asymmetric comparison with nothing to complain about it.
     */
    private static boolean checkIdentityAgreement(@NotNull ProblemsHolder holder,
                                                  @NotNull PsiClass target,
                                                  @NotNull PsiAnnotation annotation) {
        String own = identityOf(annotation);
        for (PsiClass current : WholeObjectConstants.callableSupertypes(target)) {
            PsiAnnotation above = WholeObjectConstants.find(current, FQN);
            if (above == null) continue;
            String theirs = identityOf(above);
            if (own.equals(theirs)) return false;
            holder.registerProblem(identityAnchor(annotation),
                LABEL + "(identity = " + own + ") on " + target.getName() + " disagrees with "
                    + current.getName() + ", which asks for " + theirs + " - one hierarchy cannot "
                    + "hold two identity relations without the comparison becoming asymmetric",
                ProblemHighlightType.GENERIC_ERROR);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Attributes
    // ------------------------------------------------------------------

    /** The identity relation the annotation asks for, defaulted the way the annotation is. */
    private static @NotNull String identityOf(@NotNull PsiAnnotation annotation) {
        return WholeObjectConstants.enumAttr(annotation, WholeObjectConstants.ATTR_IDENTITY,
            WholeObjectConstants.EXACT_CLASS);
    }

    /** The written {@code identity} value, falling back to the annotation when it is defaulted. */
    private static @NotNull PsiElement identityAnchor(@NotNull PsiAnnotation annotation) {
        PsiAnnotationMemberValue value =
            WholeObjectConstants.written(annotation, WholeObjectConstants.ATTR_IDENTITY);
        return value != null ? value : annotation;
    }

    /** Whether the generated members call the superclass's own. */
    private static boolean callsSuper(@NotNull PsiClass target, @NotNull PsiAnnotation annotation) {
        return WholeObjectConstants.callSuper(target,
            WholeObjectConstants.enumAttr(annotation, WholeObjectConstants.ATTR_CALL_SUPER, "AUTO"),
            FQN, Signature.EQUALS, Signature.HASH_CODE);
    }

    /**
     * Reports {@code callSuper = YES} on a type whose superclass supplies
     * nothing to call - the inherited implementation is identity-based, and
     * calling it would defeat the generated member outright.
     */
    private static void checkCallSuper(@NotNull ProblemsHolder holder, @NotNull PsiClass target,
                                       @NotNull PsiAnnotation annotation) {
        PsiAnnotationMemberValue value =
            WholeObjectConstants.written(annotation, WholeObjectConstants.ATTR_CALL_SUPER);
        if (value == null) return;
        if (!"YES".equals(WholeObjectConstants.enumAttr(annotation,
            WholeObjectConstants.ATTR_CALL_SUPER, "AUTO"))) return;
        if (WholeObjectConstants.callableSuperclass(target) != null) return;
        holder.registerProblem(value,
            LABEL + "(callSuper = YES) on " + target.getName() + ", whose superclass supplies no "
                + "implementation to call - the inherited one is identity-based and would defeat "
                + "the generated member",
            ProblemHighlightType.GENERIC_ERROR);
    }

    /**
     * Reports a superclass supplying one half of the pair and not the other,
     * which leaves {@code callSuper = AUTO} with no answer.
     *
     * <p>The check the resolution cannot make on the caller's behalf, and the one
     * whose absence inverts what the inspection half is for: the same resolved
     * {@code false} means "no super call" and "no answer", the processor rejects
     * the second outright, and without this the file is green in the IDE and red
     * on the next build.
     */
    private static void checkCallSuperResolvable(@NotNull ProblemsHolder holder,
                                                 @NotNull PsiClass target,
                                                 @NotNull PsiAnnotation annotation) {
        if (!WholeObjectConstants.callSuperUnresolvable(target,
            WholeObjectConstants.enumAttr(annotation, WholeObjectConstants.ATTR_CALL_SUPER, "AUTO"),
            FQN, Signature.EQUALS, Signature.HASH_CODE)) return;
        PsiClass superclass = WholeObjectConstants.callableSuperclass(target);
        if (superclass == null) return;
        PsiAnnotationMemberValue value =
            WholeObjectConstants.written(annotation, WholeObjectConstants.ATTR_CALL_SUPER);
        holder.registerProblem(value != null ? value : annotation,
            LABEL + " cannot resolve callSuper on " + target.getName() + " - "
                + superclass.getName() + " declares some of the pair and not the rest, which is "
                + "already inconsistent. Write callSuper explicitly to say which behaviour you want",
            ProblemHighlightType.GENERIC_ERROR);
    }

    /**
     * Reports the two narrowing attributes contradicting each other or naming a
     * member the selection never reaches, and returns what is left.
     *
     * <p>The unmatched-name report is the check earning those attributes their
     * keep: a rename leaves the string behind, and the member it used to name
     * silently rejoins or leaves the relation with nothing failing.
     *
     * @param holder the problems holder
     * @param target the annotated type
     * @param annotation the {@code @EqualsAndHashCode} annotation
     * @return the members the generated pair reads
     */
    private static @NotNull List<Selected> checkNarrowing(@NotNull ProblemsHolder holder,
                                                          @NotNull PsiClass target,
                                                          @NotNull PsiAnnotation annotation) {
        List<Selected> candidates = WholeObjectConstants.candidates(target, POLICY);
        List<String> of = WholeObjectConstants.names(annotation, WholeObjectConstants.ATTR_OF);
        List<String> exclude =
            WholeObjectConstants.names(annotation, WholeObjectConstants.ATTR_EXCLUDE);
        if (!of.isEmpty() && !exclude.isEmpty()) {
            holder.registerProblem(annotation,
                LABEL + " sets both 'of' and 'exclude' - they are two spellings of one choice and "
                    + "cannot both apply",
                ProblemHighlightType.GENERIC_ERROR);
            return candidates;
        }
        List<String> selectable = new ArrayList<>(candidates.size());
        for (Selected member : candidates) selectable.add(member.name());
        reportUnmatched(holder, target, annotation, WholeObjectConstants.ATTR_OF, selectable);
        reportUnmatched(holder, target, annotation, WholeObjectConstants.ATTR_EXCLUDE, selectable);
        return WholeObjectConstants.narrow(candidates, of, exclude);
    }

    private static void reportUnmatched(@NotNull ProblemsHolder holder, @NotNull PsiClass target,
                                        @NotNull PsiAnnotation annotation, @NotNull String attribute,
                                        @NotNull List<String> selectable) {
        for (PsiAnnotationMemberValue entry :
            WholeObjectConstants.entries(annotation, attribute)) {
            String name = WholeObjectConstants.stringValue(entry);
            if (name == null || selectable.contains(name)) continue;
            PsiMethod candidate = WholeObjectConstants.includableCandidate(target, name);
            String because = candidate == null
                ? "which is not a member this selection reaches"
                : "which is a method rather than a field - mark it @"
                    + WholeObjectConstants.simpleName(POLICY.includeFqn())
                    + " to make it a member";
            holder.registerProblem(entry,
                LABEL + "(" + attribute + ") names '" + name + "', " + because,
                ProblemHighlightType.GENERIC_ERROR);
        }
    }

    /**
     * Reports the three shapes the hash memo cannot be sound in.
     *
     * <p>A stale memo is a contract break with no compile signal, so a member
     * that can change after construction is named individually rather than
     * summarised.
     */
    private static void checkCache(@NotNull ProblemsHolder holder, @NotNull PsiClass target,
                                   @NotNull PsiAnnotation annotation,
                                   @NotNull List<Selected> members) {
        if (!WholeObjectConstants.booleanAttr(annotation,
            WholeObjectConstants.ATTR_CACHE_HASH_CODE, false)) return;
        PsiAnnotationMemberValue value =
            WholeObjectConstants.written(annotation, WholeObjectConstants.ATTR_CACHE_HASH_CODE);
        PsiElement anchor = value != null ? value : annotation;
        if (target.isRecord()) {
            holder.registerProblem(anchor,
                LABEL + "(cacheHashCode) on the record " + target.getName() + " - a record body "
                    + "cannot declare the instance field the memo needs. Drop cacheHashCode, or "
                    + "make the type a final class",
                ProblemHighlightType.GENERIC_ERROR);
            return;
        }
        for (Selected member : members) {
            if (!member.mutable() || member.anchor() == null) continue;
            holder.registerProblem(member.anchor(),
                LABEL + "(cacheHashCode) with the mutable member '" + member.name() + "' - the memo "
                    + "is only sound while every compared member is fixed at construction",
                ProblemHighlightType.WARNING);
        }
        if (callsSuper(target, annotation)) {
            holder.registerProblem(anchor,
                LABEL + "(cacheHashCode) with callSuper - the superclass's contribution may change "
                    + "after construction and its finality is not decidable here, so the memo can "
                    + "go stale",
                ProblemHighlightType.WARNING);
        }
    }

    // ------------------------------------------------------------------
    // Members
    // ------------------------------------------------------------------

    /**
     * Reports the marker pair on a field or a record component.
     *
     * @param holder the problems holder
     * @param member the field or component carrying the markers
     * @param name its name
     * @param owner the type declaring it
     */
    private static void checkMarkers(@NotNull ProblemsHolder holder,
                                     @NotNull PsiModifierListOwner member, @Nullable String name,
                                     @Nullable PsiClass owner) {
        if (name == null || owner == null) return;
        PsiAnnotation exclude = WholeObjectConstants.find(member, EXCLUDE_FQN);
        PsiAnnotation include = WholeObjectConstants.find(member, INCLUDE_FQN);
        if (exclude == null && include == null) {
            checkBuilderIgnore(holder, member, name, owner, null);
            return;
        }
        if (exclude != null && include != null) {
            holder.registerProblem(include,
                "'" + name + "' carries both the include and exclude markers for " + LABEL
                    + " - keep one",
                ProblemHighlightType.GENERIC_ERROR);
            return;
        }
        if (!WholeObjectConstants.has(owner, FQN)) {
            reportInertMarker(holder, exclude != null ? exclude : include, owner);
            return;
        }
        if (include != null && WholeObjectConstants.has(member, WholeObjectConstants.LAZY_FQN)) {
            holder.registerProblem(include,
                "'" + name + "' is @Lazy, so " + LABEL + " cannot read it directly - declare a "
                    + "zero-arg method carrying the include marker if the memoized value belongs "
                    + "here",
                ProblemHighlightType.GENERIC_ERROR);
            return;
        }
        checkBuilderIgnore(holder, member, name, owner, exclude);
    }

    /**
     * Reports the marker on a method, which the include marker can promote to a
     * term only where it holds a per-instance value to contribute.
     */
    private static void checkIncludedMethod(@NotNull ProblemsHolder holder,
                                            @NotNull PsiMethod method) {
        PsiAnnotation include = WholeObjectConstants.find(method, INCLUDE_FQN);
        PsiAnnotation exclude = WholeObjectConstants.find(method, EXCLUDE_FQN);
        if (include == null && exclude == null) return;
        PsiClass owner = method.getContainingClass();
        if (owner == null) return;
        if (include != null && exclude != null) {
            holder.registerProblem(include,
                "'" + method.getName() + "' carries both the include and exclude markers for "
                    + LABEL + " - keep one",
                ProblemHighlightType.GENERIC_ERROR);
            return;
        }
        if (!WholeObjectConstants.has(owner, FQN)) {
            reportInertMarker(holder, include != null ? include : exclude, owner);
            return;
        }
        if (include == null || WholeObjectConstants.includable(method)) return;
        String reason;
        if (method.hasModifierProperty(PsiModifier.STATIC)) {
            reason = "is static, so it holds no per-instance value to contribute to " + LABEL;
        } else if (!method.getParameterList().isEmpty()) {
            reason = "takes parameters, so " + LABEL + " has nothing to pass it - only a zero-arg "
                + "method can be included";
        } else {
            reason = "returns void, so it produces no value for " + LABEL;
        }
        holder.registerProblem(include, "'" + method.getName() + "' " + reason,
            ProblemHighlightType.GENERIC_ERROR);
    }

    private static void reportInertMarker(@NotNull ProblemsHolder holder,
                                          @NotNull PsiAnnotation marker, @NotNull PsiClass owner) {
        String written = marker.getNameReferenceElement() == null
            ? "The marker"
            : "@" + marker.getNameReferenceElement().getReferenceName();
        holder.registerProblem(marker,
            written + " is never read - " + owner.getName() + " carries no " + LABEL
                + " for it to narrow",
            ProblemHighlightType.WARNING);
    }

    /**
     * Reports a field the builder skips but the equality relation still reads.
     *
     * <p>The two selections are deliberately independent - {@code @BuilderIgnore}
     * says the caller does not supply the field, not that it is outside the
     * value - so this is a prompt rather than a rejection.
     *
     * <p>The prompt is only true of a member the relation <b>does</b> read, which
     * is why the resolved selection is consulted rather than the annotation's
     * presence. A {@code transient} field is already dropped by this policy, and
     * so is a {@code @Lazy} one, a static, a {@code $} name and anything either
     * narrowing attribute removes - on all of which the report would name a
     * reconciliation that has already happened and offer a marker that changes
     * nothing. The walk is paid only once the ignore marker is found.
     */
    private static void checkBuilderIgnore(@NotNull ProblemsHolder holder,
                                           @NotNull PsiModifierListOwner member,
                                           @NotNull String name, @NotNull PsiClass owner,
                                           @Nullable PsiAnnotation exclude) {
        if (exclude != null) return;
        PsiAnnotation type = WholeObjectConstants.find(owner, FQN);
        if (type == null) return;
        PsiAnnotation ignore =
            WholeObjectConstants.find(member, WholeObjectConstants.BUILDER_IGNORE_FQN);
        if (ignore == null) return;
        if (!WholeObjectConstants.reaches(owner, type, POLICY, name)) return;
        holder.registerProblem(ignore,
            "@BuilderIgnore does not take '" + name + "' out of " + LABEL + " - the builder's field "
                + "set is not the equality set, and a field the caller does not supply is still "
                + "part of the value. Write @EqualsExclude as well if it should not be compared",
            ProblemHighlightType.WEAK_WARNING, excludeFixes(type, name));
    }

    /**
     * The exclude-marker fix, offered only where writing the marker would leave
     * one statement about the member rather than two.
     *
     * <p>A member the type-level {@code of} or {@code exclude} already names is
     * spoken for there, and the marker takes it out of the selection those
     * attributes are matched against - so the offered fix would trade a prompt
     * for the hard error that reports a name the selection can no longer reach.
     * Editing the list instead is not a local fix and not a safe one: dropping
     * the last name from {@code of} widens the relation to every field, which is
     * the opposite of what it was written to say. The report stands without a
     * fix, and which of the two statements to keep is the author's to choose.
     */
    private static @NotNull LocalQuickFix[] excludeFixes(@NotNull PsiAnnotation type,
                                                         @NotNull String name) {
        return WholeObjectConstants.namedByNarrowing(type, name)
            ? new LocalQuickFix[0]
            : new LocalQuickFix[]{new AddExcludeFix(name)};
    }

    /**
     * Reports what a member's declared type makes impossible.
     *
     * <p>Deliberately silent on a plain array of any depth: {@code byte[]},
     * {@code int[][]} and {@code Vector3f[]} are all compared correctly by the
     * flat and deep split, and warning on them would train the reader to ignore
     * the one case that is genuinely unreachable. The failure is an array
     * <b>behind a type parameter</b>, not array nesting.
     */
    private static void checkMemberTypes(@NotNull ProblemsHolder holder,
                                         @NotNull PsiAnnotation annotation,
                                         @NotNull List<Selected> members) {
        for (Selected member : members) {
            PsiType type = member.type();
            PsiElement anchor = member.anchor();
            if (type == null || anchor == null) continue;
            PsiType wrapped = arrayAmongParameters(type);
            if (wrapped != null) {
                holder.registerProblem(anchor,
                    "'" + member.name() + "' is a " + type.getPresentableText() + " - the "
                        + wrapped.getPresentableText() + " elements compare by identity, not "
                        + "content. The container delegates to its element's equals, and an array "
                        + "inherits Object's",
                    ProblemHighlightType.WARNING, excludeFixes(annotation, member.name()));
            }
            if (isBareCollection(type)) {
                holder.registerProblem(anchor,
                    "'" + member.name() + "' is declared java.util.Collection, which specifies no "
                        + "equals contract at all - two collections holding the same elements are "
                        + "not required to compare equal. Declare it as List or Set",
                    ProblemHighlightType.WARNING);
            }
            if (overridesNoEquals(type)) {
                holder.registerProblem(anchor,
                    "'" + member.name() + "' is a " + type.getPresentableText() + ", which declares "
                        + "no equals of its own, so comparing it reduces to reference identity",
                    ProblemHighlightType.WEAK_WARNING);
            }
        }
    }

    /** The first array found among a type's arguments, at any nesting. */
    private static @Nullable PsiType arrayAmongParameters(@NotNull PsiType type) {
        if (!(type instanceof PsiClassType classType)) return null;
        for (PsiType parameter : classType.getParameters()) {
            if (parameter == null) continue;
            if (parameter.getArrayDimensions() > 0) return parameter;
            PsiType nested = arrayAmongParameters(parameter);
            if (nested != null) return nested;
        }
        return null;
    }

    private static boolean isBareCollection(@NotNull PsiType type) {
        if (!(type instanceof PsiClassType classType)) return false;
        PsiClass declared = classType.resolve();
        return declared != null && COLLECTION_FQN.equals(declared.getQualifiedName());
    }

    /**
     * Whether nothing between the declared type and {@code Object} supplies an
     * {@code equals}.
     *
     * <p>A record and an enum count as supplying one whether or not it is
     * written, and an interface is left alone: what an implementation supplies
     * is not decidable from the declared type. So does a type carrying this
     * annotation itself, whose pair is written by the build rather than in
     * source - reporting there would complain about exactly the shape the
     * feature exists to produce.
     */
    private static boolean overridesNoEquals(@NotNull PsiType type) {
        if (!(type instanceof PsiClassType classType)) return false;
        PsiClass declared = classType.resolve();
        if (declared == null || declared instanceof PsiTypeParameter) return false;
        for (PsiClass current : WholeObjectConstants.chainFrom(declared, false)) {
            if (current.isInterface() || current.isRecord() || current.isEnum()) return false;
            if (OBJECT_FQN.equals(current.getQualifiedName())) return true;
            if (WholeObjectConstants.find(current,
                WholeObjectConstants.EQUALS_AND_HASH_CODE_FQN) != null) {
                return false;
            }
            if (WholeObjectConstants.declares(current, Signature.EQUALS) != null) return false;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // The three checks that are not mirrors
    // ------------------------------------------------------------------

    /**
     * Recommends the cooperating relation on a type a framework subclasses.
     *
     * <p>{@code EXACT_CLASS} is the default because it can only ever be narrow
     * where the alternatives can be silently wrong. Its one real cost is a proxy
     * comparing unequal to the instance it stands for, and this check is what
     * keeps that from stranding a proxy-backed type: it names the two shapes
     * where a subclass is made at runtime rather than written in source.
     */
    private static void checkIdentityRecommendation(@NotNull ProblemsHolder holder,
                                                    @NotNull PsiClass target,
                                                    @NotNull PsiAnnotation annotation) {
        if (!WholeObjectConstants.EXACT_CLASS.equals(identityOf(annotation))) return;
        // A type nothing can subclass has no proxy to be stranded by.
        if (target.isRecord() || target.hasModifierProperty(PsiModifier.FINAL)) return;
        String reason = proxyReason(target);
        if (reason == null) return;
        holder.registerProblem(identityAnchor(annotation),
            target.getName() + " " + reason + ", so a framework subclasses it at runtime to stand "
                + "in for state it has not loaded - identity = EXACT_CLASS compares that stand-in "
                + "unequal to the instance it represents, and unequal in the other direction too. "
                + "INSTANCE_OF_CANEQUAL keeps the relation symmetric across the pair",
            ProblemHighlightType.WEAK_WARNING, new SetIdentityFix());
    }

    /** Why a subclass of this type is expected to be made rather than written, or {@code null}. */
    private static @Nullable String proxyReason(@NotNull PsiClass target) {
        for (String fqn : ENTITY_FQNS) {
            if (WholeObjectConstants.has(target, fqn)) return "carries @Entity";
        }
        for (PsiField field : WholeObjectConstants.ownFields(target)) {
            if (field instanceof PsiEnumConstant) continue;
            if (!lazilyFetched(field)) continue;
            return "holds the lazily-fetched association '" + field.getName() + "'";
        }
        return null;
    }

    /** Whether the field declares an association the persistence provider loads on demand. */
    private static boolean lazilyFetched(@NotNull PsiField field) {
        for (String fqn : LAZY_BY_DEFAULT) {
            PsiAnnotation association = WholeObjectConstants.find(field, fqn);
            if (association != null
                && "LAZY".equals(WholeObjectConstants.enumAttr(association, "fetch", "LAZY"))) {
                return true;
            }
        }
        for (String fqn : EAGER_BY_DEFAULT) {
            PsiAnnotation association = WholeObjectConstants.find(field, fqn);
            if (association != null
                && "LAZY".equals(WholeObjectConstants.enumAttr(association, "fetch", "EAGER"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reports the cooperation hook asked for on a type no subclass can reach,
     * where the bare {@code instanceof} relation is emitted instead.
     *
     * <p>Silent once the author has written a {@code canEqual} of their own. That
     * declaration is reused rather than replaced, and the reuse is settled before
     * the degradation is: the call is emitted, so reporting that it is not would
     * describe the wrong member.
     */
    private static void checkDegradedHook(@NotNull ProblemsHolder holder, @NotNull PsiClass target,
                                          @NotNull PsiAnnotation annotation) {
        if (!WholeObjectConstants.INSTANCE_OF_CANEQUAL.equals(identityOf(annotation))) return;
        if (WholeObjectConstants.declaredOverload(target, WholeObjectConstants.CAN_EQUAL, 1)
            != null) return;
        boolean sealedOff = target.isRecord() || target.hasModifierProperty(PsiModifier.FINAL);
        if (!sealedOff || WholeObjectConstants.callableSuperclass(target) != null) return;
        holder.registerProblem(identityAnchor(annotation),
            LABEL + "(identity = INSTANCE_OF_CANEQUAL) on the final type " + target.getName()
                + " - no subclass can override the hook, so the bare instanceof relation is "
                + "emitted instead",
            ProblemHighlightType.WEAK_WARNING);
    }

    /**
     * Reports an author-written {@code canEqual} the generated {@code equals}
     * cannot use, or that nothing can override.
     *
     * <p>The shape became load bearing the moment the hook started being
     * <b>reused</b> rather than skipped: an author who writes it is opting into
     * the protocol, so the generated relation calls their declaration instead of
     * emitting one of its own. That reuse is matched on the name and the argument
     * count alone, which puts the rest of the shape on the author - a hook
     * returning something other than {@code boolean}, or taking something
     * {@code this} cannot be passed as, fails inside a member nobody wrote and on
     * a line nobody can see.
     *
     * <p>The overridability half is not a compile failure and is the more likely
     * mistake. A hook no subclass can reach leaves the relation bare
     * {@code instanceof} while reading exactly like the cooperating one, which is
     * the asymmetry the hook exists to remove.
     */
    private static void checkHookShape(@NotNull ProblemsHolder holder, @NotNull PsiClass target,
                                       @NotNull PsiAnnotation annotation) {
        if (!WholeObjectConstants.INSTANCE_OF_CANEQUAL.equals(identityOf(annotation))) return;
        PsiMethod hook =
            WholeObjectConstants.declaredOverload(target, WholeObjectConstants.CAN_EQUAL, 1);
        if (hook == null) return;
        PsiElement anchor = hook.getNameIdentifier() != null ? hook.getNameIdentifier() : hook;
        String reused = LABEL + " calls this declaration rather than generating one - ";

        PsiType returnType = hook.getReturnType();
        if (returnType == null || !(PsiTypes.booleanType().equals(returnType)
            || returnType.equalsToText("java.lang.Boolean"))) {
            holder.registerProblem(anchor,
                "'canEqual' returns "
                    + (returnType == null ? "nothing" : returnType.getPresentableText()) + ". "
                    + reused + "the generated equals negates the result, so the hook has to return "
                    + "boolean",
                ProblemHighlightType.GENERIC_ERROR);
            return;
        }

        PsiType parameter = hook.getParameterList().getParameters()[0].getType();
        PsiType targetType = JavaPsiFacade.getElementFactory(target.getProject()).createType(target);
        if (!parameter.isAssignableFrom(targetType)) {
            holder.registerProblem(anchor,
                "'canEqual' takes " + parameter.getPresentableText() + ". " + reused
                    + "the generated equals asks the other instance back with this, which no "
                    + target.getName() + " can be passed as",
                ProblemHighlightType.GENERIC_ERROR);
            return;
        }
        if (!parameter.equalsToText(OBJECT_FQN)) {
            holder.registerProblem(anchor,
                "'canEqual' takes " + parameter.getPresentableText() + " rather than Object. "
                    + reused + "a subclass overriding the protected boolean canEqual(Object) the "
                    + "protocol is written in declares a different method, and the relation stays "
                    + "asymmetric with nothing to say so",
                ProblemHighlightType.WARNING);
            return;
        }
        // A type nothing can subclass has no override to be denied, which is the
        // same reason the degraded-hook report stops there.
        if (target.isRecord() || target.hasModifierProperty(PsiModifier.FINAL)) return;
        String sealed = unoverridable(hook);
        if (sealed == null) return;
        holder.registerProblem(anchor,
            "'canEqual' is " + sealed + ", so no subclass can override it. " + reused
                + "the relation then accepts every subclass and the cooperation the hook exists for "
                + "is instanceof in all but name - declare it protected",
            ProblemHighlightType.WARNING);
    }

    /** Why no subclass can override the hook, or {@code null} when one can. */
    private static @Nullable String unoverridable(@NotNull PsiMethod hook) {
        if (hook.hasModifierProperty(PsiModifier.STATIC)) return "static";
        if (hook.hasModifierProperty(PsiModifier.FINAL)) return "final";
        if (hook.hasModifierProperty(PsiModifier.PRIVATE)) return "private";
        if (hook.hasModifierProperty(PsiModifier.PACKAGE_LOCAL)) return "package-private";
        return null;
    }

    /**
     * Reports a subclass that overrides {@code equals} without overriding the
     * hook an annotated ancestor's relation calls.
     *
     * <p>The one failure no emission can prevent: the subclass may not exist at
     * processing time, or even in the same compilation, so nothing the processor
     * sees can catch it.
     */
    private static void checkSubclassHook(@NotNull ProblemsHolder holder, @NotNull PsiClass target) {
        if (target.isInterface() || target.isAnnotationType()) return;
        PsiMethod equals = WholeObjectConstants.declares(target, Signature.EQUALS);
        if (equals == null || equals.hasModifierProperty(PsiModifier.ABSTRACT)) return;
        if (WholeObjectConstants.declares(target, Signature.CAN_EQUAL_HOOK) != null) return;
        PsiClass ancestor = hookedAncestor(target);
        if (ancestor == null) return;
        PsiElement anchor = equals.getNameIdentifier() != null ? equals.getNameIdentifier() : equals;
        holder.registerProblem(anchor,
            target.getName() + " overrides equals but not canEqual, which " + ancestor.getName()
                + "'s generated relation calls - the inherited hook accepts every "
                + ancestor.getName() + ", so a " + ancestor.getName() + " compares equal to a "
                + target.getName() + " that does not compare equal back",
            ProblemHighlightType.WARNING, new AddCanEqualFix(target.getName()));
    }

    /** The nearest ancestor whose generated relation calls the hook, or {@code null}. */
    private static @Nullable PsiClass hookedAncestor(@NotNull PsiClass target) {
        for (PsiClass current : WholeObjectConstants.callableSupertypes(target)) {
            PsiAnnotation above = WholeObjectConstants.find(current, FQN);
            if (above == null) continue;
            return WholeObjectConstants.INSTANCE_OF_CANEQUAL.equals(identityOf(above))
                ? current
                : null;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Quick fixes
    // ------------------------------------------------------------------

    /**
     * Rewrites the type-level annotation to the cooperating relation, carrying
     * every other attribute across.
     */
    private static final class SetIdentityFix implements LocalQuickFix {

        @Override
        public @NotNull String getFamilyName() {
            return "Compare with instanceof and a canEqual hook";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiClass target =
                PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiClass.class, false);
            if (target == null) return;
            PsiModifierList modifiers = target.getModifierList();
            if (modifiers == null) return;
            PsiAnnotation written = WholeObjectConstants.find(target, FQN);
            if (written == null) return;

            List<String> attributes = new ArrayList<>();
            attributes.add(WholeObjectConstants.ATTR_IDENTITY + " = "
                + WholeObjectConstants.IDENTITY_FQN + "."
                + WholeObjectConstants.INSTANCE_OF_CANEQUAL);
            // Everything else the annotation said is about what is compared
            // rather than about who may be compared, and survives the change
            // untouched.
            for (PsiNameValuePair pair : written.getParameterList().getAttributes()) {
                String name = pair.getName();
                if (name == null || WholeObjectConstants.ATTR_IDENTITY.equals(name)) continue;
                PsiAnnotationMemberValue value = pair.getValue();
                if (value != null) attributes.add(name + " = " + value.getText());
            }
            PsiElement replaced = written.replace(JavaPsiFacade.getElementFactory(project)
                .createAnnotationFromText(
                    "@" + FQN + "(" + String.join(", ", attributes) + ")", modifiers));
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(replaced);
        }
    }

    /**
     * Writes the hook the subclass is missing, mirroring the shape the processor
     * emits on the annotated ancestor.
     */
    private static final class AddCanEqualFix implements LocalQuickFix {

        private final @Nullable String targetName;

        AddCanEqualFix(@Nullable String targetName) {
            this.targetName = targetName;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Override 'canEqual'";
        }

        @Override
        public @NotNull String getName() {
            return this.targetName == null
                ? getFamilyName()
                : "Override 'canEqual' in '" + this.targetName + "'";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiClass target =
                PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiClass.class, false);
            if (target == null || target.getName() == null) return;
            PsiMethod hook = JavaPsiFacade.getElementFactory(project).createMethodFromText(
                "protected boolean " + WholeObjectConstants.CAN_EQUAL + "(Object other) {\n"
                    + "    return other instanceof " + reference(target) + ";\n"
                    + "}", target);
            PsiMethod equals = WholeObjectConstants.declares(target, Signature.EQUALS);
            PsiElement added = equals != null ? target.addAfter(hook, equals) : target.add(hook);
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(added);
        }

        /**
         * The target spelled as a type test, wildcarded when it is generic - a
         * raw test would compile with a warning the fix has no business
         * introducing.
         */
        private static @NotNull String reference(@NotNull PsiClass target) {
            PsiTypeParameter[] parameters = target.getTypeParameters();
            if (parameters.length == 0) return String.valueOf(target.getName());
            List<String> wildcards = new ArrayList<>(parameters.length);
            for (int i = 0; i < parameters.length; i++) wildcards.add("?");
            return target.getName() + "<" + String.join(", ", wildcards) + ">";
        }
    }

    /** Writes the exclude marker onto the member the report was about. */
    private static final class AddExcludeFix implements LocalQuickFix {

        private final @NotNull String memberName;

        AddExcludeFix(@NotNull String memberName) {
            this.memberName = memberName;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Exclude the member with @EqualsExclude";
        }

        @Override
        public @NotNull String getName() {
            return "Exclude '" + this.memberName + "' with @EqualsExclude";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiModifierListOwner member = PsiTreeUtil.getParentOfType(descriptor.getPsiElement(),
                PsiModifierListOwner.class, false);
            if (member == null) return;
            PsiModifierList modifiers = member.getModifierList();
            if (modifiers == null) return;
            if (WholeObjectConstants.find(member, EXCLUDE_FQN) != null) return;
            JavaCodeStyleManager.getInstance(project)
                .shortenClassReferences(modifiers.addAnnotation(EXCLUDE_FQN));
        }
    }

}
