package dev.simplified.equality.inspect;

import com.intellij.codeInsight.FileModificationService;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.CommonClassNames;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiBinaryExpression;
import com.intellij.psi.PsiBlockStatement;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiDeclarationStatement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiIfStatement;
import com.intellij.psi.PsiInstanceOfExpression;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiPolyadicExpression;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeCastExpression;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import dev.simplified.annotations.EqualsAndHashCode;
import dev.simplified.equality.inspect.WholeObjectConstants.Selected;
import dev.simplified.equality.inspect.WholeObjectConstants.Signature;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports a hand-written {@code equals} and {@code hashCode} pair that
 * {@link EqualsAndHashCode} would generate, and offers to replace it.
 *
 * <p>The counterpart to {@link EqualityConsistencyInspection}, which reports the
 * pairs the annotation <b>cannot</b> adopt. Between them a class carrying a
 * hand-written pair gets an answer either way, which is what makes the two
 * together an inventory rather than a nag.
 *
 * <p><b>The match is exact, and that is the whole safety argument.</b> Every
 * statement of the {@code equals} has to be a recognised guard, the cast local,
 * or the returned chain; every term of that chain has to be the comparison the
 * annotation emits for that member's declared type. A guard that reads state, a
 * term this does not recognise, a {@code float} compared with bare {@code ==}
 * where the annotation emits {@code Float.compare} - each one means the
 * generated relation could differ from the written one, and each one drops the
 * class from the report entirely rather than reporting it with a caveat.
 *
 * <p><b>{@code hashCode} is held to a weaker standard on purpose.</b> It has to
 * read the same members, and nothing more. A hash value is not part of any
 * contract - only its consistency with {@code equals} is - so requiring the
 * accumulator shape as well would reject almost every hand-written pair to
 * protect a number nobody may depend on.
 *
 * <p>The one difference the report tolerates is an array member the written
 * {@code equals} compares by reference. Adopting the annotation switches it to
 * content comparison, so <b>equality changes</b> - and that change is the reason
 * the annotation exists. It is reported in its own words, with a fix whose name
 * says what it does, rather than folded in with the pairs that change nothing.
 */
public class ReplaceableEqualityInspection extends LocalInspectionTool {

    private static final String IDENTITY_FQN = "dev.simplified.annotations.EqualsAndHashCode.Identity";
    private static final String CALL_SUPER_FQN = "dev.simplified.annotations.CallSuper";
    private static final String OBJECTS_FQN = "java.util.Objects";
    private static final String ARRAYS_FQN = "java.util.Arrays";

    /**
     * How a written term compares one member. These name the shape the author
     * wrote rather than the type it was written over, which is why {@code ==}
     * is one constant covering every type it can legally appear on.
     */
    private enum Comparison { OPERATOR_EQ, NUMERIC_COMPARE, OBJECTS_EQUALS, ARRAYS_EQUALS, ARRAYS_DEEP_EQUALS }

    /** How a written term lines up with what the annotation would emit. */
    private enum Verdict { EXACT, ARRAY_BY_REFERENCE, MISMATCH }

    private record Term(@NotNull String member, @NotNull Comparison comparison) {}

    private record Shape(@NotNull String identity, boolean callSuper, @NotNull List<Term> terms) {}

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,
                                                   boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitClass(@NotNull PsiClass target) {
                super.visitClass(target);
                check(holder, target);
            }
        };
    }

    private static void check(@NotNull ProblemsHolder holder, @NotNull PsiClass target) {
        if (target.isInterface() || target.isAnnotationType() || target.isEnum()) return;
        if (target.getAnnotation(WholeObjectConstants.EQUALS_AND_HASH_CODE_FQN) != null) return;

        PsiMethod equals = WholeObjectConstants.declares(target, Signature.EQUALS);
        PsiMethod hashCode = WholeObjectConstants.declares(target, Signature.HASH_CODE);
        if (equals == null || hashCode == null) return;

        Set<String> comparedBy = HandWrittenEquality.reads(equals, target);
        Set<String> hashedBy = HandWrittenEquality.reads(hashCode, target);
        if (comparedBy == null || hashedBy == null) return;
        if (comparedBy.isEmpty() || !comparedBy.equals(hashedBy)) return;

        Shape shape = analyse(equals, target);
        if (shape == null) return;

        Set<String> members = new LinkedHashSet<>(comparedBy);
        boolean callSuper = members.remove(HandWrittenEquality.SUPER);
        if (callSuper != shape.callSuper()) return;
        if (!sameMembers(shape.terms(), members)) return;

        Plan plan = plan(target, members, shape);
        if (plan == null) return;

        PsiElement anchor = equals.getNameIdentifier();
        if (anchor == null) return;

        if (plan.byReference().isEmpty()) {
            holder.registerProblem(anchor,
                "@EqualsAndHashCode" + plan.attributes() + " generates this pair - replacing the "
                    + "two methods with the annotation emits the same relation",
                ProblemHighlightType.WEAK_WARNING, new ReplaceFix(false));
            return;
        }
        holder.registerProblem(anchor,
            "@EqualsAndHashCode" + plan.attributes() + " generates this pair, except that it "
                + "compares " + render(plan.byReference()) + " by content where this equals "
                + "compares " + (plan.byReference().size() == 1 ? "it" : "them")
                + " by reference. Adopting it changes - and most likely fixes - equality for "
                + (plan.byReference().size() == 1 ? "that member" : "those members"),
            ProblemHighlightType.WEAK_WARNING, new ReplaceFix(true));
    }

    // ------------------------------------------------------------------
    // Reading the written equals
    // ------------------------------------------------------------------

    /**
     * The written {@code equals} as an identity relation, a super fold and a
     * list of terms, or {@code null} when any part of it is a shape this does
     * not recognise.
     *
     * <p>Every statement has to be accounted for. An unrecognised one means the
     * method does something the annotation does not, and there is no way to tell
     * from here whether that something matters.
     */
    private static @Nullable Shape analyse(@NotNull PsiMethod equals, @NotNull PsiClass target) {
        PsiCodeBlock body = equals.getBody();
        if (body == null) return null;
        PsiStatement[] statements = body.getStatements();
        if (statements.length == 0) return null;
        if (!(statements[statements.length - 1] instanceof PsiReturnStatement returned)) return null;
        PsiExpression chain = returned.getReturnValue();
        if (chain == null) return null;

        String identity = null;
        boolean callSuper = false;
        for (int i = 0; i < statements.length - 1; i++) {
            PsiStatement statement = statements[i];
            if (statement instanceof PsiDeclarationStatement declaration) {
                if (!isCastLocal(declaration)) return null;
                continue;
            }
            if (!(statement instanceof PsiIfStatement guard)) return null;
            PsiExpression condition = guard.getCondition();
            if (condition == null) return null;

            Set<String> touched = HandWrittenEquality.reads(condition, target);
            if (touched == null) return null;
            boolean foldsSuper = touched.remove(HandWrittenEquality.SUPER);
            // A guard reading the object's own state is doing work the identity
            // relation does not do, so the pair is not this annotation's shape.
            if (!touched.isEmpty()) return null;

            Boolean constant = returnedConstant(guard);
            if (constant == null) return null;
            if (foldsSuper) {
                if (constant) return null;
                callSuper = true;
                continue;
            }
            // The `this == o` shortcut, which the annotation emits too.
            if (constant) continue;
            String found = identityOf(condition);
            if (found == null || identity != null) return null;
            identity = found;
        }
        if (identity == null) return null;

        List<Term> terms = terms(chain, target);
        if (terms == null || terms.isEmpty()) return null;
        return new Shape(identity, callSuper, terms);
    }

    /** The identity relation a guard expresses, or {@code null} if it is none of the three. */
    private static @Nullable String identityOf(@NotNull PsiExpression condition) {
        for (PsiMethodCallExpression call
            : PsiTreeUtil.findChildrenOfType(condition, PsiMethodCallExpression.class)) {
            if (WholeObjectConstants.CAN_EQUAL.equals(call.getMethodExpression().getReferenceName())) {
                return "INSTANCE_OF_CANEQUAL";
            }
        }
        if (!PsiTreeUtil.findChildrenOfType(condition, PsiInstanceOfExpression.class).isEmpty()) {
            return "INSTANCE_OF";
        }
        for (PsiMethodCallExpression call
            : PsiTreeUtil.findChildrenOfType(condition, PsiMethodCallExpression.class)) {
            if ("getClass".equals(call.getMethodExpression().getReferenceName())) return "EXACT_CLASS";
        }
        return null;
    }

    /** Whether a guard's whole job is to return one boolean constant. */
    private static @Nullable Boolean returnedConstant(@NotNull PsiIfStatement guard) {
        if (guard.getElseBranch() != null) return null;
        PsiStatement single = guard.getThenBranch();
        if (single instanceof PsiBlockStatement block) {
            PsiStatement[] inner = block.getCodeBlock().getStatements();
            if (inner.length != 1) return null;
            single = inner[0];
        }
        if (!(single instanceof PsiReturnStatement returned)) return null;
        PsiExpression value = PsiUtil.skipParenthesizedExprDown(returned.getReturnValue());
        if (!(value instanceof PsiLiteralExpression literal)) return null;
        Object constant = literal.getValue();
        return constant instanceof Boolean flag ? flag : null;
    }

    private static boolean isCastLocal(@NotNull PsiDeclarationStatement declaration) {
        PsiElement[] declared = declaration.getDeclaredElements();
        if (declared.length != 1 || !(declared[0] instanceof PsiLocalVariable local)) return false;
        return PsiUtil.skipParenthesizedExprDown(local.getInitializer()) instanceof PsiTypeCastExpression;
    }

    // ------------------------------------------------------------------
    // Reading the terms
    // ------------------------------------------------------------------

    private static @Nullable List<Term> terms(@NotNull PsiExpression chain, @NotNull PsiClass target) {
        List<Term> out = new ArrayList<>();
        for (PsiExpression conjunct : conjuncts(chain)) {
            Term term = term(conjunct, target);
            if (term == null) return null;
            out.add(term);
        }
        return out;
    }

    private static @NotNull List<PsiExpression> conjuncts(@NotNull PsiExpression chain) {
        List<PsiExpression> out = new ArrayList<>();
        flatten(chain, out);
        return out;
    }

    private static void flatten(@Nullable PsiExpression expression, @NotNull List<PsiExpression> out) {
        PsiExpression bare = PsiUtil.skipParenthesizedExprDown(expression);
        if (bare == null) return;
        if (bare instanceof PsiPolyadicExpression polyadic
            && JavaTokenType.ANDAND.equals(polyadic.getOperationTokenType())) {
            for (PsiExpression operand : polyadic.getOperands()) flatten(operand, out);
            return;
        }
        out.add(bare);
    }

    private static @Nullable Term term(@NotNull PsiExpression conjunct, @NotNull PsiClass target) {
        if (conjunct instanceof PsiMethodCallExpression call) {
            String owner = staticOwner(call);
            String name = call.getMethodExpression().getReferenceName();
            String member = pairedMember(call, target);
            if (member == null || owner == null || name == null) return null;
            if (OBJECTS_FQN.equals(owner) && "equals".equals(name)) {
                return new Term(member, Comparison.OBJECTS_EQUALS);
            }
            if (ARRAYS_FQN.equals(owner) && "equals".equals(name)) {
                return new Term(member, Comparison.ARRAYS_EQUALS);
            }
            if (ARRAYS_FQN.equals(owner) && "deepEquals".equals(name)) {
                return new Term(member, Comparison.ARRAYS_DEEP_EQUALS);
            }
            return null;
        }
        if (!(conjunct instanceof PsiBinaryExpression binary)
            || !JavaTokenType.EQEQ.equals(binary.getOperationTokenType())) {
            return null;
        }
        // Float.compare(a, b) == 0, which is what the annotation emits for a
        // float or a double.
        PsiExpression left = PsiUtil.skipParenthesizedExprDown(binary.getLOperand());
        PsiExpression right = PsiUtil.skipParenthesizedExprDown(binary.getROperand());
        if (left instanceof PsiMethodCallExpression call && isZero(right)) {
            String owner = staticOwner(call);
            String member = pairedMember(call, target);
            if (member != null
                && ("java.lang.Float".equals(owner) || "java.lang.Double".equals(owner))
                && "compare".equals(call.getMethodExpression().getReferenceName())) {
                return new Term(member, Comparison.NUMERIC_COMPARE);
            }
            return null;
        }
        String member = sameMember(left, right, target);
        return member == null ? null : new Term(member, Comparison.OPERATOR_EQ);
    }

    private static boolean isZero(@Nullable PsiExpression expression) {
        return expression instanceof PsiLiteralExpression literal
            && Integer.valueOf(0).equals(literal.getValue());
    }

    /** The class a static call is made on, so a helper of the same name elsewhere is not mistaken for it. */
    private static @Nullable String staticOwner(@NotNull PsiMethodCallExpression call) {
        PsiMethod resolved = call.resolveMethod();
        PsiClass owner = resolved == null ? null : resolved.getContainingClass();
        return owner == null ? null : owner.getQualifiedName();
    }

    /** The single member a two-argument comparison reads on both sides. */
    private static @Nullable String pairedMember(@NotNull PsiMethodCallExpression call,
                                                 @NotNull PsiClass target) {
        PsiExpression[] arguments = call.getArgumentList().getExpressions();
        if (arguments.length != 2) return null;
        return sameMember(arguments[0], arguments[1], target);
    }

    /**
     * The one member both sides read, or {@code null} when they read different
     * members, more than one, or none - each of which means the term is not a
     * plain comparison of one member across the two objects.
     */
    private static @Nullable String sameMember(@Nullable PsiExpression left,
                                               @Nullable PsiExpression right,
                                               @NotNull PsiClass target) {
        if (left == null || right == null) return null;
        Set<String> onLeft = HandWrittenEquality.reads(left, target);
        Set<String> onRight = HandWrittenEquality.reads(right, target);
        if (onLeft == null || onRight == null) return null;
        if (onLeft.size() != 1 || !onLeft.equals(onRight)) return null;
        return onLeft.iterator().next();
    }

    private static boolean sameMembers(@NotNull List<Term> terms, @NotNull Set<String> members) {
        Set<String> named = new LinkedHashSet<>();
        for (Term term : terms) named.add(term.member());
        return named.equals(members);
    }

    // ------------------------------------------------------------------
    // Planning the replacement
    // ------------------------------------------------------------------

    /**
     * What the annotation has to say to reproduce the written pair.
     *
     * @param attributes the attribute list, already rendered, empty when every default holds
     * @param marked members the selection does not reach on its own, to carry an include marker
     * @param byReference array members the written equals compares by reference
     */
    private record Plan(@NotNull String attributes, @NotNull List<PsiModifierListOwner> marked,
                        @NotNull List<String> byReference) {}

    private static @Nullable Plan plan(@NotNull PsiClass target, @NotNull Set<String> members,
                                       @NotNull Shape shape) {
        Map<String, PsiType> selectable = new LinkedHashMap<>();
        for (Selected candidate : WholeObjectConstants.candidates(target, WholeObjectConstants.EQUALITY_POLICY)) {
            selectable.put(candidate.name(), candidate.type());
        }

        List<PsiModifierListOwner> marked = new ArrayList<>();
        List<String> byReference = new ArrayList<>();
        for (Term term : shape.terms()) {
            PsiType type = selectable.get(term.member());
            if (type == null) {
                // Read but never selected - a derived accessor, or state the
                // policy drops. The include marker is what reaches it, so the
                // member has to be somewhere the marker can be written.
                PsiModifierListOwner owner = markable(target, term.member());
                if (owner == null) return null;
                type = declaredType(owner);
                if (type == null) return null;
                marked.add(owner);
            }
            Verdict verdict = validate(term.comparison(), type);
            if (verdict == Verdict.MISMATCH) return null;
            if (verdict == Verdict.ARRAY_BY_REFERENCE) byReference.add(term.member());
        }

        List<String> excluded = new ArrayList<>();
        for (String name : selectable.keySet()) {
            if (!members.contains(name)) excluded.add(name);
        }

        List<String> attributes = new ArrayList<>(3);
        if (!"EXACT_CLASS".equals(shape.identity())) {
            attributes.add("identity = " + IDENTITY_FQN + "." + shape.identity());
        }
        // AUTO resolves to NO against Object and is read from the supertype
        // otherwise, which is a decision this fix must not silently re-take.
        PsiClass superClass = target.getSuperClass();
        boolean hasSuper = superClass != null
            && !CommonClassNames.JAVA_LANG_OBJECT.equals(superClass.getQualifiedName());
        if (hasSuper) {
            attributes.add("callSuper = " + CALL_SUPER_FQN + "." + (shape.callSuper() ? "YES" : "NO"));
        } else if (shape.callSuper()) {
            return null;
        }
        if (!excluded.isEmpty()) attributes.add("exclude = " + arrayLiteral(excluded));

        String rendered = attributes.isEmpty() ? "" : "(" + String.join(", ", attributes) + ")";
        return new Plan(rendered, marked, byReference);
    }

    /** The field or zero-arg method an include marker could be written on. */
    private static @Nullable PsiModifierListOwner markable(@NotNull PsiClass target,
                                                           @NotNull String member) {
        PsiMethod method = WholeObjectConstants.includableCandidate(target, member);
        if (method != null) return method;
        for (PsiField field : target.getFields()) {
            if (member.equals(field.getName())) return field;
        }
        return null;
    }

    private static @Nullable PsiType declaredType(@NotNull PsiModifierListOwner owner) {
        if (owner instanceof PsiField field) return field.getType();
        if (owner instanceof PsiMethod method) return method.getReturnType();
        return null;
    }

    /**
     * Whether a declared type is an enum class.
     *
     * <p>Deliberately narrowed to the declared type rather than to what the
     * member can hold. A field typed as an interface an enum happens to
     * implement is an ordinary reference, and there {@code ==} and
     * {@code Objects.equals} part company.
     *
     * @param type the member's declared type
     * @return {@code true} if the type resolves to an enum class
     */
    private static boolean isEnum(@NotNull PsiType type) {
        PsiClass resolved = PsiUtil.resolveClassInClassTypeOnly(type);
        return resolved != null && resolved.isEnum();
    }

    /** Whether a written comparison is the one the annotation emits for that type. */
    private static @NotNull Verdict validate(@NotNull Comparison comparison, @NotNull PsiType type) {
        PsiType component = type instanceof PsiArrayType arrayType ? arrayType.getComponentType() : null;
        boolean array = component != null;
        return switch (comparison) {
            case OPERATOR_EQ -> {
                // The annotation emits Objects.equals here, which on an enum is
                // the same relation: Enum.equals is final and identity-based, so
                // the two agree on every pair, two nulls included.
                if (isEnum(type)) yield Verdict.EXACT;
                if (!(type instanceof PsiPrimitiveType)) yield Verdict.MISMATCH;
                // The annotation emits Float.compare, which separates NaN from
                // itself and -0.0 from 0.0 the other way round than ==.
                yield PsiTypes.floatType().equals(type) || PsiTypes.doubleType().equals(type)
                    ? Verdict.MISMATCH : Verdict.EXACT;
            }
            case NUMERIC_COMPARE ->
                PsiTypes.floatType().equals(type) || PsiTypes.doubleType().equals(type)
                    ? Verdict.EXACT : Verdict.MISMATCH;
            case OBJECTS_EQUALS -> {
                if (type instanceof PsiPrimitiveType) yield Verdict.MISMATCH;
                yield array ? Verdict.ARRAY_BY_REFERENCE : Verdict.EXACT;
            }
            case ARRAYS_EQUALS ->
                array && component instanceof PsiPrimitiveType ? Verdict.EXACT : Verdict.MISMATCH;
            case ARRAYS_DEEP_EQUALS ->
                array && !(component instanceof PsiPrimitiveType) ? Verdict.EXACT : Verdict.MISMATCH;
        };
    }

    private static @NotNull String arrayLiteral(@NotNull List<String> names) {
        List<String> quoted = new ArrayList<>(names.size());
        for (String name : names) quoted.add("\"" + name + "\"");
        return quoted.size() == 1 ? quoted.getFirst() : "{" + String.join(", ", quoted) + "}";
    }

    private static @NotNull String render(@NotNull List<String> names) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) out.append(i == names.size() - 1 ? " and " : ", ");
            out.append("'").append(names.get(i)).append("'");
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // The fix
    // ------------------------------------------------------------------

    private static final class ReplaceFix implements LocalQuickFix {

        private final boolean changesArrayComparison;

        ReplaceFix(boolean changesArrayComparison) {
            this.changesArrayComparison = changesArrayComparison;
        }

        @Override
        public @NotNull String getFamilyName() {
            return this.changesArrayComparison
                ? "Replace equals/hashCode with @EqualsAndHashCode (compares arrays by content)"
                : "Replace equals/hashCode with @EqualsAndHashCode";
        }

        @Override
        public boolean startInWriteAction() {
            return false;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiClass target =
                PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiClass.class, false);
            if (target == null) return;
            PsiMethod equals = WholeObjectConstants.declares(target, Signature.EQUALS);
            PsiMethod hashCode = WholeObjectConstants.declares(target, Signature.HASH_CODE);
            if (equals == null || hashCode == null) return;

            Set<String> comparedBy = HandWrittenEquality.reads(equals, target);
            if (comparedBy == null) return;
            Shape shape = analyse(equals, target);
            if (shape == null) return;
            Set<String> members = new LinkedHashSet<>(comparedBy);
            members.remove(HandWrittenEquality.SUPER);
            Plan plan = plan(target, members, shape);
            if (plan == null) return;

            PsiModifierList modifiers = target.getModifierList();
            if (modifiers == null) return;
            if (!FileModificationService.getInstance().preparePsiElementForWrite(target)) return;
            WriteCommandAction.runWriteCommandAction(project,
                () -> replace(project, target, modifiers, plan, equals, hashCode));
        }

        private void replace(@NotNull Project project, @NotNull PsiClass target,
                             @NotNull PsiModifierList modifiers, @NotNull Plan plan,
                             @NotNull PsiMethod equals, @NotNull PsiMethod hashCode) {
            for (PsiModifierListOwner owner : plan.marked()) {
                PsiModifierList list = owner.getModifierList();
                if (list != null) {
                    write(project, list, "@" + WholeObjectConstants.EQUALS_INCLUDE_FQN);
                }
            }
            write(project, modifiers,
                "@" + WholeObjectConstants.EQUALS_AND_HASH_CODE_FQN + plan.attributes());
            equals.delete();
            hashCode.delete();
        }

        private void write(@NotNull Project project, @NotNull PsiModifierList modifiers,
                           @NotNull String text) {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
            PsiAnnotation annotation = factory.createAnnotationFromText(text, modifiers);
            PsiElement added = modifiers.addBefore(annotation, modifiers.getFirstChild());
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(added);
        }

    }

}
