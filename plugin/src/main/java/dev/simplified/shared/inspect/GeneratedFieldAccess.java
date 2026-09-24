package dev.simplified.shared.inspect;

import com.intellij.psi.JavaRecursiveElementWalkingVisitor;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAssertStatement;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiBlockStatement;
import com.intellij.psi.PsiBreakStatement;
import com.intellij.psi.PsiCaseLabelElement;
import com.intellij.psi.PsiCaseLabelElementList;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiConditionalExpression;
import com.intellij.psi.PsiContinueStatement;
import com.intellij.psi.PsiDeclarationStatement;
import com.intellij.psi.PsiDefaultCaseLabelElement;
import com.intellij.psi.PsiDoWhileStatement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionListStatement;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiForStatement;
import com.intellij.psi.PsiForeachStatement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiIfStatement;
import com.intellij.psi.PsiLabeledStatement;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPattern;
import com.intellij.psi.PsiPolyadicExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiResourceExpression;
import com.intellij.psi.PsiResourceList;
import com.intellij.psi.PsiResourceVariable;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiSwitchBlock;
import com.intellij.psi.PsiSwitchExpression;
import com.intellij.psi.PsiSwitchLabelStatement;
import com.intellij.psi.PsiSwitchLabelStatementBase;
import com.intellij.psi.PsiSwitchLabeledRuleStatement;
import com.intellij.psi.PsiSwitchStatement;
import com.intellij.psi.PsiSynchronizedStatement;
import com.intellij.psi.PsiThisExpression;
import com.intellij.psi.PsiThrowStatement;
import com.intellij.psi.PsiTryStatement;
import com.intellij.psi.PsiUnaryExpression;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.PsiWhileStatement;
import com.intellij.psi.PsiYieldStatement;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import com.intellij.psi.tree.IElementType;
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
     * constructor's statements by name, by javac's definite-unassignment rules:
     * a write reached where the field is definitely unassigned is accepted, and
     * every other write - a second one, one after {@code this(..)}, one a loop
     * may run again - is refused, as javac refuses it.
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
     * <p>The body is read into {@link BlankFinalLift}'s statement and
     * expression shapes, as the processor reads the javac tree: every
     * statement form by its own shape and every expression by the operands it
     * evaluates.
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
        if (statement instanceof PsiExpressionStatement expression)
            return new BlankFinalLift.Expression<>(valueOf(expression.getExpression()));
        if (statement instanceof PsiExpressionListStatement list) {
            List<BlankFinalLift.Statement<PsiReferenceExpression>> out = new ArrayList<>();
            for (PsiExpression expression : list.getExpressionList().getExpressions())
                out.add(new BlankFinalLift.Expression<>(valueOf(expression)));
            return new BlankFinalLift.Block<>(out);
        }
        if (statement instanceof PsiDeclarationStatement declaration) {
            List<BlankFinalLift.Statement<PsiReferenceExpression>> out = new ArrayList<>();
            for (PsiElement element : declaration.getDeclaredElements()) {
                if (element instanceof PsiLocalVariable local)
                    out.add(new BlankFinalLift.Expression<>(valueOf(local.getInitializer())));
            }
            return new BlankFinalLift.Block<>(out);
        }
        if (statement instanceof PsiBlockStatement block) return blockOf(block.getCodeBlock());
        if (statement instanceof PsiIfStatement branch) {
            PsiStatement otherwise = branch.getElseBranch();
            return new BlankFinalLift.Branch<>(valueOf(branch.getCondition()), statementOf(branch.getThenBranch()),
                otherwise == null ? null : statementOf(otherwise));
        }
        if (statement instanceof PsiWhileStatement loop)
            return loop(BlankFinalLift.LoopKind.WHILE, List.of(), loop.getCondition(), List.of(), loop.getBody());
        if (statement instanceof PsiDoWhileStatement loop)
            return loop(BlankFinalLift.LoopKind.DO, List.of(), loop.getCondition(), List.of(), loop.getBody());
        if (statement instanceof PsiForStatement loop) {
            return loop(BlankFinalLift.LoopKind.FOR, List.of(statementOf(loop.getInitialization())),
                loop.getCondition(), List.of(statementOf(loop.getUpdate())), loop.getBody());
        }
        if (statement instanceof PsiForeachStatement loop) {
            return new BlankFinalLift.Loop<>(BlankFinalLift.LoopKind.FOREACH,
                List.of(new BlankFinalLift.Expression<>(valueOf(loop.getIteratedValue()))), null, List.of(),
                statementOf(loop.getBody()));
        }
        if (statement instanceof PsiSwitchStatement choice)
            return new BlankFinalLift.Switch<>(valueOf(choice.getExpression()), exhaustive(choice), armsOf(choice, false));
        if (statement instanceof PsiTryStatement attempt) return tryOf(attempt);
        if (statement instanceof PsiSynchronizedStatement lock) {
            return new BlankFinalLift.Block<>(List.of(
                new BlankFinalLift.Expression<>(valueOf(lock.getLockExpression())), blockOf(lock.getBody())));
        }
        if (statement instanceof PsiLabeledStatement labelled)
            return new BlankFinalLift.Labelled<>(labelled.getLabelIdentifier().getText(),
                statementOf(labelled.getStatement()));
        if (statement instanceof PsiReturnStatement)
            return new BlankFinalLift.Jump<>(BlankFinalLift.JumpKind.RETURN, null, null);
        if (statement instanceof PsiThrowStatement thrown)
            return new BlankFinalLift.Jump<>(BlankFinalLift.JumpKind.THROW, null, valueOf(thrown.getException()));
        if (statement instanceof PsiBreakStatement jump)
            return new BlankFinalLift.Jump<>(BlankFinalLift.JumpKind.BREAK, labelOf(jump.getLabelIdentifier()), null);
        if (statement instanceof PsiContinueStatement jump) {
            return new BlankFinalLift.Jump<>(BlankFinalLift.JumpKind.CONTINUE, labelOf(jump.getLabelIdentifier()),
                null);
        }
        if (statement instanceof PsiYieldStatement yielded)
            return new BlankFinalLift.Jump<>(BlankFinalLift.JumpKind.YIELD, null, valueOf(yielded.getExpression()));
        if (statement instanceof PsiAssertStatement check) {
            PsiExpression detail = check.getAssertDescription();
            return new BlankFinalLift.Assert<>(valueOf(check.getAssertCondition()),
                detail == null ? null : valueOf(detail));
        }
        return new BlankFinalLift.Block<>(List.of());
    }

    /**
     * Reads a code block into the rule's shape.
     *
     * @param block the block, or {@code null} where the source leaves it out
     * @return its shape
     */
    private static @NotNull BlankFinalLift.Statement<PsiReferenceExpression> blockOf(@Nullable PsiCodeBlock block) {
        return new BlankFinalLift.Block<>(block == null ? List.of() : statementsOf(block.getStatements()));
    }

    /**
     * Reads a loop into the rule's shape.
     *
     * @param kind which loop it is
     * @param init what runs once ahead of it
     * @param condition the condition, or {@code null}
     * @param update the update statements
     * @param body the body, or {@code null} where the source leaves it out
     * @return its shape
     */
    private static @NotNull BlankFinalLift.Statement<PsiReferenceExpression> loop(
        @NotNull BlankFinalLift.LoopKind kind, @NotNull List<BlankFinalLift.Statement<PsiReferenceExpression>> init,
        @Nullable PsiExpression condition, @NotNull List<BlankFinalLift.Statement<PsiReferenceExpression>> update,
        @Nullable PsiStatement body) {
        return new BlankFinalLift.Loop<>(kind, init, condition == null ? null : valueOf(condition), update,
            statementOf(body));
    }

    /**
     * Reads a {@code try} statement into the rule's shape, its resources ahead
     * of its block.
     *
     * @param attempt the statement
     * @return its shape
     */
    private static @NotNull BlankFinalLift.Statement<PsiReferenceExpression> tryOf(@NotNull PsiTryStatement attempt) {
        List<BlankFinalLift.Statement<PsiReferenceExpression>> body = new ArrayList<>();
        PsiResourceList resources = attempt.getResourceList();
        if (resources != null) {
            for (PsiElement resource : resources.getChildren()) {
                if (resource instanceof PsiResourceVariable variable)
                    body.add(new BlankFinalLift.Expression<>(valueOf(variable.getInitializer())));
                else if (resource instanceof PsiResourceExpression expression)
                    body.add(new BlankFinalLift.Expression<>(valueOf(expression.getExpression())));
            }
        }
        PsiCodeBlock block = attempt.getTryBlock();
        if (block != null) body.addAll(statementsOf(block.getStatements()));
        List<List<BlankFinalLift.Statement<PsiReferenceExpression>>> catches = new ArrayList<>();
        for (PsiCodeBlock handler : attempt.getCatchBlocks()) catches.add(statementsOf(handler.getStatements()));
        PsiCodeBlock finalizer = attempt.getFinallyBlock();
        return new BlankFinalLift.Try<>(body, catches, finalizer == null ? null : statementsOf(finalizer.getStatements()));
    }

    /**
     * The text of a jump's label.
     *
     * @param label the label, or {@code null}
     * @return its name, or {@code null}
     */
    private static @Nullable String labelOf(@Nullable PsiIdentifier label) {
        return label == null ? null : label.getText();
    }

    /**
     * Reads an expression into the rule's shapes, by the operands it
     * evaluates and the names it writes.
     *
     * @param expression the expression, or {@code null} where the source leaves one out
     * @return its shape
     */
    private static @NotNull BlankFinalLift.Value<PsiReferenceExpression> valueOf(@Nullable PsiExpression expression) {
        PsiExpression tree = PsiUtil.skipParenthesizedExprDown(expression);
        if (tree == null || tree instanceof PsiLambdaExpression) return new BlankFinalLift.Evaluate<>(List.of());
        if (tree instanceof PsiLiteralExpression literal && literal.getValue() instanceof Boolean value)
            return new BlankFinalLift.Constant<>(value);
        if (tree instanceof PsiAssignmentExpression assignment) {
            return new BlankFinalLift.Assign<>(
                List.of(valueOf(assignment.getLExpression()), valueOf(assignment.getRExpression())),
                writeOf(assignment.getLExpression()));
        }
        if (tree instanceof PsiUnaryExpression unary) {
            IElementType operator = unary.getOperationTokenType();
            if (operator == JavaTokenType.EXCL) return new BlankFinalLift.Not<>(valueOf(unary.getOperand()));
            if (operator == JavaTokenType.PLUSPLUS || operator == JavaTokenType.MINUSMINUS)
                return new BlankFinalLift.Assign<>(List.of(valueOf(unary.getOperand())), writeOf(unary.getOperand()));
        }
        if (tree instanceof PsiPolyadicExpression polyadic) {
            IElementType operator = polyadic.getOperationTokenType();
            if (operator == JavaTokenType.ANDAND || operator == JavaTokenType.OROR) {
                PsiExpression[] operands = polyadic.getOperands();
                BlankFinalLift.Value<PsiReferenceExpression> combined = valueOf(operands[0]);
                for (int i = 1; i < operands.length; i++) {
                    combined = operator == JavaTokenType.ANDAND
                        ? new BlankFinalLift.And<>(combined, valueOf(operands[i]))
                        : new BlankFinalLift.Or<>(combined, valueOf(operands[i]));
                }
                return combined;
            }
        }
        if (tree instanceof PsiConditionalExpression choice) {
            return new BlankFinalLift.Choice<>(valueOf(choice.getCondition()), valueOf(choice.getThenExpression()),
                valueOf(choice.getElseExpression()));
        }
        if (tree instanceof PsiSwitchExpression choice)
            return new BlankFinalLift.SwitchValue<>(valueOf(choice.getExpression()), armsOf(choice, true));
        List<BlankFinalLift.Value<PsiReferenceExpression>> operands = new ArrayList<>();
        for (PsiElement child = tree.getFirstChild(); child != null; child = child.getNextSibling())
            collectOperands(child, operands);
        return new BlankFinalLift.Evaluate<>(operands);
    }

    /**
     * Collects the outermost expressions under an element, in source order,
     * leaving out a nested class's body.
     *
     * @param element the element
     * @param out where each expression's shape goes
     */
    private static void collectOperands(@NotNull PsiElement element,
                                        @NotNull List<BlankFinalLift.Value<PsiReferenceExpression>> out) {
        if (element instanceof PsiExpression expression) {
            out.add(valueOf(expression));
            return;
        }
        if (element instanceof PsiClass || element instanceof PsiCodeBlock || element instanceof PsiStatement) return;
        for (PsiElement child = element.getFirstChild(); child != null; child = child.getNextSibling())
            collectOperands(child, out);
    }

    /**
     * Reads the target of a write.
     *
     * @param target the written expression
     * @return the write, or {@code null} when its target is not a bare name or an unqualified {@code this} one
     */
    private static @Nullable BlankFinalLift.Write<PsiReferenceExpression> writeOf(@Nullable PsiExpression target) {
        if (!(PsiUtil.skipParenthesizedExprDown(target) instanceof PsiReferenceExpression reference)) return null;
        String name = reference.getReferenceName();
        if (name == null) return null;
        PsiExpression qualifier = PsiUtil.skipParenthesizedExprDown(reference.getQualifierExpression());
        if (qualifier == null) return new BlankFinalLift.Write<>(name, false, reference);
        if (qualifier instanceof PsiThisExpression self && self.getQualifier() == null)
            return new BlankFinalLift.Write<>(name, true, reference);
        return null;
    }

    /**
     * Reads a {@code switch}'s body into its arms, consecutive colon labels
     * joining one arm.
     *
     * @param choice the switch statement or expression
     * @param yields whether it is an expression, whose arrow arm with an expression body yields it
     * @return the arms
     */
    private static @NotNull List<BlankFinalLift.Arm<PsiReferenceExpression>> armsOf(@NotNull PsiSwitchBlock choice,
                                                                                    boolean yields) {
        List<BlankFinalLift.Arm<PsiReferenceExpression>> arms = new ArrayList<>();
        PsiCodeBlock body = choice.getBody();
        if (body == null) return arms;
        List<BlankFinalLift.Statement<PsiReferenceExpression>> group = null;
        for (PsiStatement statement : body.getStatements()) {
            if (statement instanceof PsiSwitchLabeledRuleStatement rule) {
                PsiStatement ruleBody = rule.getBody();
                BlankFinalLift.Statement<PsiReferenceExpression> shape =
                    yields && ruleBody instanceof PsiExpressionStatement expression
                        ? new BlankFinalLift.Jump<>(BlankFinalLift.JumpKind.YIELD, null,
                            valueOf(expression.getExpression()))
                        : statementOf(ruleBody);
                arms.add(new BlankFinalLift.Arm<>(true, List.of(shape)));
            } else if (statement instanceof PsiSwitchLabelStatement) {
                if (group == null || !group.isEmpty()) {
                    group = new ArrayList<>();
                    arms.add(new BlankFinalLift.Arm<>(false, group));
                }
            } else if (group != null) {
                group.add(statementOf(statement));
            }
        }
        return arms;
    }

    /**
     * Whether a {@code switch}'s labels make javac require it to cover every
     * value - a {@code default}, a pattern or a {@code null} label.
     *
     * @param choice the switch statement
     * @return whether it is exhaustive
     */
    private static boolean exhaustive(@NotNull PsiSwitchBlock choice) {
        PsiCodeBlock body = choice.getBody();
        if (body == null) return false;
        for (PsiStatement statement : body.getStatements()) {
            if (!(statement instanceof PsiSwitchLabelStatementBase label)) continue;
            if (label.isDefaultCase()) return true;
            PsiCaseLabelElementList elements = label.getCaseLabelElementList();
            if (elements == null) continue;
            for (PsiCaseLabelElement element : elements.getElements()) {
                if (element instanceof PsiDefaultCaseLabelElement || element instanceof PsiPattern) return true;
                if (element instanceof PsiLiteralExpression literal && "null".equals(literal.getText())) return true;
            }
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
