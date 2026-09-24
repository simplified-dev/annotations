package dev.simplified.shared.inspect;

import com.intellij.psi.JavaRecursiveElementWalkingVisitor;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiBlockStatement;
import com.intellij.psi.PsiBreakStatement;
import com.intellij.psi.PsiCaseLabelElement;
import com.intellij.psi.PsiCaseLabelElementList;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiDefaultCaseLabelElement;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiIfStatement;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiSwitchLabelStatement;
import com.intellij.psi.PsiSwitchLabelStatementBase;
import com.intellij.psi.PsiSwitchLabeledRuleStatement;
import com.intellij.psi.PsiSwitchStatement;
import com.intellij.psi.PsiThisExpression;
import com.intellij.psi.PsiThrowStatement;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import dev.simplified.accessor.inspect.AccessorConstants;
import dev.simplified.args.apt.ArgsMode;
import dev.simplified.args.editor.ArgsInference;
import dev.simplified.args.inspect.ArgsConstants;
import dev.simplified.classbuilder.apt.BlankFinalLift;
import dev.simplified.classbuilder.editor.ClassBuilderAugmentProvider;
import dev.simplified.classbuilder.inspect.ClassBuilderConstants;
import dev.simplified.equality.inspect.WholeObjectConstants;
import dev.simplified.shared.psi.WrittenAnnotations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Answers what a generated member does to a field, for the two extension points
 * that have to unsay a platform analysis the augment providers alone cannot
 * reach.
 *
 * <p>An augment provider makes a generated member <b>resolve</b> - a
 * {@code new Target(...)} binds, a {@code getX()} call site binds - and that is
 * all it does. Two platform analyses walk source rather than the augmented
 * member list: definite assignment, which decides a {@code final} field is
 * unassigned because no <i>written</i> constructor assigns it, and reference
 * search, which decides a field is unread because the only reader is a light
 * method that holds no reference into the source tree. Both then report on a
 * line the author did not write, about source javac compiles. This class is the
 * single place that decides which fields those reports are wrong about, so the
 * filter and the suppressor cannot drift into disagreeing.
 *
 * <p>Every selection question is delegated to {@link ArgsConstants} and
 * {@link ArgsInference}, which route through the same {@code ArgsSelection} the
 * processor calls. Re-deciding "is this field a constructor parameter" here is
 * exactly how a suppression comes to cover a field javac still rejects.
 */
public final class GeneratedFieldAccess {

    private GeneratedFieldAccess() {
    }

    /**
     * Whether a generated constructor assigns the field.
     *
     * <p>The question definite-assignment analysis is really asking. A written
     * constructor annotation answers it through its own mode; a bare
     * {@code @ClassBuilder} answers it through the constructor it infers, which
     * is {@code null} the moment the author writes either a constructor or one
     * of the four annotations, so the two paths never both claim a field.
     *
     * @param field the field a report landed on
     * @return whether some generated constructor assigns it
     */
    public static boolean constructorAssigns(@NotNull PsiField field) {
        PsiClass owner = owningClass(field);
        if (owner == null) return false;

        for (PsiAnnotation annotation : ArgsConstants.written(owner)) {
            ArgsMode mode = ArgsConstants.modeOf(annotation);
            if (mode == null) continue;
            // AccessLevel.NONE generates no constructor, so it assigns nothing.
            if (ArgsConstants.accessKeyword(annotation, mode) == null) continue;
            List<String> exclude = mode == ArgsMode.BUILDER ? builderExclude(owner) : List.of();
            if (names(ArgsConstants.select(owner, mode, exclude), field)) return true;
            // force fills the finals no parameter covers.
            if (ArgsConstants.force(annotation)
                && names(ArgsConstants.unassignedFinals(owner), field)) return true;
        }

        ArgsInference inferred = ArgsInference.of(owner);
        return inferred != null && names(inferred.fields(), field);
    }

    /**
     * Whether {@code @ClassBuilder} lifts the field's initializer off it.
     *
     * <p>{@code retainInit} keeps a {@code final} field's initializer as the
     * builder's default and the AST pass strips it from the field, leaving a
     * blank final for the constructor to assign. In source the field still reads
     * as an initialized {@code final}, so an assignment to it is
     * {@code Cannot assign a value to final variable} - correct about the text,
     * wrong about the class javac emits.
     *
     * <p>Independent of {@link #constructorAssigns} rather than derived from it:
     * the lift happens because the field is in the builder's selection, whether
     * or not the builder also synthesises the constructor that assigns it. A
     * target with a hand-written constructor gets the lift and no synthesised
     * constructor, and that is the shape this exists for - unless one of those
     * constructors assigns the field nowhere, which {@link BlankFinalLift}
     * decides from the written constructors as the processor does, and the
     * field then keeps its initializer. So does a field beside a written
     * constructor and a Lombok constructor annotation, Lombok's constructor
     * assigning no initialized {@code final}. With no written constructor the
     * field is lifted only where a constructor {@code @ClassBuilder} generates
     * assigns it - never under a {@code factoryMethod} or beside an author's
     * own {@code build()}.
     *
     * @param field the field a report landed on
     * @return whether the field is a lifted blank final
     */
    public static boolean liftedBlankFinal(@NotNull PsiField field) {
        if (!field.hasModifierProperty(PsiModifier.FINAL)) return false;
        if (!field.hasInitializer()) return false;
        PsiClass owner = owningClass(field);
        if (owner == null) return false;
        if (owner.getAnnotation(ClassBuilderConstants.ANNOTATION_FQN) == null) return false;
        List<PsiField> selected = ArgsConstants.select(owner, ArgsMode.BUILDER, builderExclude(owner));
        if (!names(selected, field)) return false;
        List<BlankFinalLift.Writes> constructors = new ArrayList<>(writtenConstructors(owner));
        constructors.addAll(BlankFinalLift.lombokConstructors(lombokAnnotations(owner), !constructors.isEmpty()));
        return BlankFinalLift.lifts(field.getName(), constructors,
            ClassBuilderAugmentProvider.generatedConstructorAssigns(owner));
    }

    /**
     * Whether a write to a lifted blank final, written in a constructor of its
     * class, is one javac accepts.
     *
     * <p>{@link BlankFinalLift#acceptedWrites} answers it from the
     * constructor's statements by name: a write reached while the field is
     * still unassigned on every path is accepted, and every other write -
     * a second one, one after {@code this(..)}, one in a loop or a {@code try} -
     * is refused, as javac refuses it.
     *
     * @param written the written reference
     * @param field the field it writes
     * @return whether javac accepts the write
     */
    public static boolean constructorAcceptsWrite(@NotNull PsiReferenceExpression written, @NotNull PsiField field) {
        PsiMethod constructor = PsiTreeUtil.getParentOfType(written, PsiMethod.class);
        if (constructor == null || !constructor.isConstructor()) return false;
        PsiCodeBlock body = constructor.getBody();
        if (body == null) return false;
        return BlankFinalLift.acceptedWrites(field.getName(), callsThis(body), declaredNames(constructor, body),
            statementsOf(body.getStatements())).contains(written);
    }

    /**
     * The qualified names of Lombok's constructor annotations written on a class.
     *
     * @param owner the class
     * @return the names, matched against the written reference and the file's imports without resolving
     */
    private static @NotNull List<String> lombokAnnotations(@NotNull PsiClass owner) {
        List<String> out = new ArrayList<>();
        for (String fqn : BlankFinalLift.LOMBOK_CONSTRUCTOR_ANNOTATIONS) {
            if (WrittenAnnotations.hasOnMember(owner, fqn)) out.add(fqn);
        }
        return out;
    }

    /**
     * Summarises each constructor written on the class for {@link BlankFinalLift}.
     *
     * <p>Read through {@link PsiExtensibleClass#getOwnMethods()}, so a
     * constructor an augment provider contributes is not among them - the
     * processor leaves out every constructor it generates the same way.
     *
     * @param owner the field's class
     * @return one summary per written constructor
     */
    private static @NotNull List<BlankFinalLift.Writes> writtenConstructors(@NotNull PsiClass owner) {
        List<PsiMethod> methods = owner instanceof PsiExtensibleClass extensible
            ? extensible.getOwnMethods()
            : List.of(owner.getMethods());
        List<BlankFinalLift.Writes> out = new ArrayList<>();
        for (PsiMethod method : methods) {
            if (!method.isConstructor() || !method.isPhysical()) continue;
            PsiCodeBlock body = method.getBody();
            if (body != null) out.add(writesOf(method, body));
        }
        return out;
    }

    /**
     * Reads the statements one constructor body writes through and the names
     * it declares, by name and without resolving.
     *
     * <p>The body is read into {@link BlankFinalLift}'s statement shapes, as
     * the processor reads the javac tree: a plain assignment or a chain of
     * them, a block, an {@code if}, a {@code switch}, an exit and a
     * {@code break}, every other statement being one the rule never counts.
     *
     * @param constructor the written constructor
     * @param body its body
     * @return its summary
     */
    private static @NotNull BlankFinalLift.Writes writesOf(@NotNull PsiMethod constructor,
                                                           @NotNull PsiCodeBlock body) {
        return BlankFinalLift.Writes.of(callsThis(body), declaredNames(constructor, body),
            statementsOf(body.getStatements()));
    }

    /**
     * The parameter and local variable names a constructor declares, at any
     * depth but a nested class's body.
     *
     * @param constructor the constructor
     * @param body its body
     * @return the names
     */
    private static @NotNull Set<String> declaredNames(@NotNull PsiMethod constructor, @NotNull PsiCodeBlock body) {
        Set<String> declared = new HashSet<>();
        for (PsiParameter parameter : constructor.getParameterList().getParameters())
            declared.add(parameter.getName());
        body.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override
            public void visitClass(@NotNull PsiClass aClass) {
            }

            @Override
            public void visitVariable(@NotNull PsiVariable variable) {
                declared.add(variable.getName());
                super.visitVariable(variable);
            }
        });
        return declared;
    }

    /**
     * Reads a statement list into the rule's shapes.
     *
     * @param statements the statements
     * @return one shape per statement
     */
    private static @NotNull List<BlankFinalLift.Statement<PsiReferenceExpression>> statementsOf(
        @NotNull PsiStatement @NotNull [] statements) {
        List<BlankFinalLift.Statement<PsiReferenceExpression>> out = new ArrayList<>();
        for (PsiStatement statement : statements) out.add(statementOf(statement));
        return out;
    }

    /**
     * Reads one statement into the rule's shapes.
     *
     * @param statement the statement, or {@code null} where the source leaves one out
     * @return its shape
     */
    private static @NotNull BlankFinalLift.Statement<PsiReferenceExpression> statementOf(
        @Nullable PsiStatement statement) {
        if (statement == null) return new BlankFinalLift.Other<>(List.of());
        if (statement instanceof PsiExpressionStatement expression) {
            PsiExpression top = PsiUtil.skipParenthesizedExprDown(expression.getExpression());
            if (top instanceof PsiAssignmentExpression assignment
                && assignment.getOperationTokenType() == JavaTokenType.EQ)
                return new BlankFinalLift.Assignment<>(chainOf(assignment));
        }
        if (statement instanceof PsiBlockStatement block)
            return new BlankFinalLift.Block<>(statementsOf(block.getCodeBlock().getStatements()));
        if (statement instanceof PsiIfStatement branch) {
            PsiStatement otherwise = branch.getElseBranch();
            return new BlankFinalLift.Branch<>(statementOf(branch.getThenBranch()),
                otherwise == null ? null : statementOf(otherwise));
        }
        if (statement instanceof PsiSwitchStatement choice) return switchOf(choice);
        if (statement instanceof PsiReturnStatement || statement instanceof PsiThrowStatement)
            return new BlankFinalLift.Exit<>();
        if (statement instanceof PsiBreakStatement jump && jump.getLabelIdentifier() == null)
            return new BlankFinalLift.Break<>();
        return new BlankFinalLift.Other<>(writesWithin(statement));
    }

    /**
     * Reads a chain of plain assignments, keeping each target written through
     * its bare name or an unqualified {@code this}.
     *
     * @param first the outermost assignment
     * @return the targets, the innermost first
     */
    private static @NotNull List<BlankFinalLift.Write<PsiReferenceExpression>> chainOf(
        @NotNull PsiAssignmentExpression first) {
        List<BlankFinalLift.Write<PsiReferenceExpression>> out = new ArrayList<>();
        PsiExpression step = first;
        while (step instanceof PsiAssignmentExpression assignment
            && assignment.getOperationTokenType() == JavaTokenType.EQ) {
            BlankFinalLift.Write<PsiReferenceExpression> write = writeOf(assignment);
            if (write != null) out.add(write);
            step = PsiUtil.skipParenthesizedExprDown(assignment.getRExpression());
        }
        Collections.reverse(out);
        return out;
    }

    /**
     * Reads the target of one plain assignment.
     *
     * @param assignment the assignment
     * @return the write, or {@code null} when its target is not a bare name or an unqualified {@code this} one
     */
    private static @Nullable BlankFinalLift.Write<PsiReferenceExpression> writeOf(
        @NotNull PsiAssignmentExpression assignment) {
        if (!(PsiUtil.skipParenthesizedExprDown(assignment.getLExpression()) instanceof PsiReferenceExpression reference))
            return null;
        String name = reference.getReferenceName();
        if (name == null) return null;
        PsiExpression qualifier = PsiUtil.skipParenthesizedExprDown(reference.getQualifierExpression());
        if (qualifier == null) return new BlankFinalLift.Write<>(name, false, reference);
        if (qualifier instanceof PsiThisExpression self && self.getQualifier() == null)
            return new BlankFinalLift.Write<>(name, true, reference);
        return null;
    }

    /**
     * Collects the plain assignments inside a statement the rule never counts,
     * outside any lambda or nested class.
     *
     * @param statement the statement
     * @return its writes
     */
    private static @NotNull List<BlankFinalLift.Write<PsiReferenceExpression>> writesWithin(
        @NotNull PsiStatement statement) {
        List<BlankFinalLift.Write<PsiReferenceExpression>> out = new ArrayList<>();
        statement.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override
            public void visitClass(@NotNull PsiClass aClass) {
            }

            @Override
            public void visitLambdaExpression(@NotNull PsiLambdaExpression expression) {
            }

            @Override
            public void visitAssignmentExpression(@NotNull PsiAssignmentExpression expression) {
                super.visitAssignmentExpression(expression);
                if (expression.getOperationTokenType() != JavaTokenType.EQ) return;
                BlankFinalLift.Write<PsiReferenceExpression> write = writeOf(expression);
                if (write != null) out.add(write);
            }
        });
        return out;
    }

    /**
     * Reads a {@code switch} into its arms, consecutive colon labels joining
     * one arm.
     *
     * @param choice the switch statement
     * @return its shape
     */
    private static @NotNull BlankFinalLift.Statement<PsiReferenceExpression> switchOf(
        @NotNull PsiSwitchStatement choice) {
        PsiCodeBlock body = choice.getBody();
        if (body == null) return new BlankFinalLift.Other<>(writesWithin(choice));
        boolean hasDefault = false;
        List<BlankFinalLift.Arm<PsiReferenceExpression>> arms = new ArrayList<>();
        List<BlankFinalLift.Statement<PsiReferenceExpression>> group = null;
        for (PsiStatement statement : body.getStatements()) {
            if (statement instanceof PsiSwitchLabeledRuleStatement rule) {
                hasDefault |= isDefault(rule);
                PsiStatement ruleBody = rule.getBody();
                arms.add(new BlankFinalLift.Arm<>(true,
                    ruleBody == null ? List.of() : List.of(statementOf(ruleBody))));
            } else if (statement instanceof PsiSwitchLabelStatement label) {
                hasDefault |= isDefault(label);
                if (group == null || !group.isEmpty()) {
                    group = new ArrayList<>();
                    arms.add(new BlankFinalLift.Arm<>(false, group));
                }
            } else if (group != null) {
                group.add(statementOf(statement));
            }
        }
        return new BlankFinalLift.Switch<>(hasDefault, arms);
    }

    /**
     * Whether a switch label is, or includes, {@code default}.
     *
     * @param label the label
     * @return whether it is a default label
     */
    private static boolean isDefault(@NotNull PsiSwitchLabelStatementBase label) {
        if (label.isDefaultCase()) return true;
        PsiCaseLabelElementList elements = label.getCaseLabelElementList();
        if (elements == null) return false;
        for (PsiCaseLabelElement element : elements.getElements()) {
            if (element instanceof PsiDefaultCaseLabelElement) return true;
        }
        return false;
    }

    /**
     * Whether one of a constructor body's statements is a {@code this(..)} call.
     *
     * @param body the constructor body
     * @return whether it delegates to another constructor of its class
     */
    private static boolean callsThis(@NotNull PsiCodeBlock body) {
        for (PsiStatement statement : body.getStatements()) {
            if (statement instanceof PsiExpressionStatement expression
                && expression.getExpression() instanceof PsiMethodCallExpression call
                && call.getMethodExpression().getQualifierExpression() == null
                && "this".equals(call.getMethodExpression().getReferenceName())) return true;
        }
        return false;
    }

    /**
     * Whether a generated member reads or writes the field.
     *
     * <p>What reference search cannot see. A generated accessor is the reason a
     * field is neither unused nor convertible to a local, and the builder's
     * {@code from} / {@code mutate} and the whole-object pair read every member
     * they collect - none of which leaves a reference in the source tree.
     *
     * @param field the field a report landed on
     * @return whether some generated member touches it
     */
    public static boolean generatedMemberTouches(@NotNull PsiField field) {
        PsiClass owner = owningClass(field);
        if (owner == null) return false;
        if (accessorGenerates(owner.getAnnotation(AccessorConstants.GETTER_FQN),
            AccessorConstants.GETTER_FQN, field)) return true;
        if (accessorGenerates(owner.getAnnotation(AccessorConstants.SETTER_FQN),
            AccessorConstants.SETTER_FQN, field)) return true;
        // These three collect members wholesale rather than per field, so
        // presence on the owner is the whole test.
        if (owner.getAnnotation(ClassBuilderConstants.ANNOTATION_FQN) != null) return true;
        if (owner.getAnnotation(WholeObjectConstants.EQUALS_AND_HASH_CODE_FQN) != null) return true;
        if (owner.getAnnotation(WholeObjectConstants.TO_STRING_FQN) != null) return true;
        return constructorAssigns(field);
    }

    /**
     * The class a field belongs to, when the field is one a generated member
     * could reach.
     *
     * @param field the field to resolve
     * @return the owner, or {@code null} when no generated member reaches the field
     */
    private static @Nullable PsiClass owningClass(@NotNull PsiField field) {
        // The filters every selection applies, applied once up front so a
        // static or enum constant costs one test rather than a class walk.
        if (field instanceof PsiEnumConstant) return null;
        if (field.hasModifierProperty(PsiModifier.STATIC)) return null;
        String name = field.getName();
        if (name.startsWith("$")) return null;
        return field.getContainingClass();
    }

    /**
     * Whether a selection holds the field.
     *
     * <p>Matched by name rather than identity: a selection is built from the
     * owner's own fields, where names are unique, and the reported element may
     * be a different PSI copy of the same declaration.
     *
     * @param selection the fields a mode selects
     * @param field the field to look for
     * @return whether the selection names it
     */
    private static boolean names(@NotNull List<PsiField> selection, @NotNull PsiField field) {
        String name = field.getName();
        for (PsiField selected : selection) {
            if (name.equals(selected.getName())) return true;
        }
        return false;
    }

    /**
     * Whether the effective accessor annotation generates a member for the field.
     *
     * @param typeLevel the owner's annotation, or {@code null}
     * @param fqn the annotation's fully-qualified name
     * @param field the field to resolve
     * @return whether an accessor is generated
     */
    private static boolean accessorGenerates(@Nullable PsiAnnotation typeLevel, @NotNull String fqn,
                                             @NotNull PsiField field) {
        PsiAnnotation fieldLevel = field.getAnnotation(fqn);
        PsiAnnotation effective = fieldLevel != null ? fieldLevel
            : typeLevel != null && !AccessorConstants.excludes(typeLevel, field.getName())
                ? typeLevel : null;
        return effective != null && AccessorConstants.generates(effective);
    }

    /** The field names {@code @ClassBuilder(exclude)} drops from the builder's selection. */
    private static List<String> builderExclude(@NotNull PsiClass owner) {
        PsiAnnotation classBuilder = owner.getAnnotation(ClassBuilderConstants.ANNOTATION_FQN);
        return classBuilder == null ? List.of() : ArgsInference.excluded(classBuilder);
    }

}
