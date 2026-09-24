package dev.simplified.classbuilder.mutate;
import com.sun.source.tree.CaseTree;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.parser.ParserFactory;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCAssert;
import com.sun.tools.javac.tree.JCTree.JCAssign;
import com.sun.tools.javac.tree.JCTree.JCAssignOp;
import com.sun.tools.javac.tree.JCTree.JCBinary;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCBreak;
import com.sun.tools.javac.tree.JCTree.JCCase;
import com.sun.tools.javac.tree.JCTree.JCCatch;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCConditional;
import com.sun.tools.javac.tree.JCTree.JCContinue;
import com.sun.tools.javac.tree.JCTree.JCDoWhileLoop;
import com.sun.tools.javac.tree.JCTree.JCEnhancedForLoop;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCExpressionStatement;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCForLoop;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCIf;
import com.sun.tools.javac.tree.JCTree.JCLabeledStatement;
import com.sun.tools.javac.tree.JCTree.JCLambda;
import com.sun.tools.javac.tree.JCTree.JCLiteral;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCNewClass;
import com.sun.tools.javac.tree.JCTree.JCReturn;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCSwitch;
import com.sun.tools.javac.tree.JCTree.JCSwitchExpression;
import com.sun.tools.javac.tree.JCTree.JCSynchronized;
import com.sun.tools.javac.tree.JCTree.JCThrow;
import com.sun.tools.javac.tree.JCTree.JCTry;
import com.sun.tools.javac.tree.JCTree.JCUnary;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree.JCWhileLoop;
import com.sun.tools.javac.tree.JCTree.JCYield;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeCopier;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import com.sun.tools.javac.util.Position;
import dev.simplified.classbuilder.apt.BlankFinalLift;
import dev.simplified.classbuilder.apt.FieldSpec;
import dev.simplified.shared.apt.SourceIntrospector;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.JavacTypeFactory;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Emits a {@code private static <FieldType> $default$<fieldName>()} method on
 * the target class for every field whose declared initializer is retained.
 * The method body returns a deep-cloned copy of the original field initializer
 * expression; the generated Builder's field default becomes a call to this
 * static provider.
 *
 * <p>This mirrors Lombok's {@code @Builder.Default} plumbing: embedding the
 * initializer expression directly in the Builder's field declaration clashes
 * with javac's flow analyser (manifests as {@code Bits.incl} assertions) and
 * reparsing via {@link ParserFactory} produces a
 * tree whose internal state still confuses flow analysis. Cloning the
 * already-parsed tree from the target's own compilation unit - with symbol
 * and type pointers reset so javac re-attributes in the method-body scope -
 * is the approach that works reliably across the JDK 17 - 25 matrix.
 *
 * <p>Because the expression now lives inside a normal method body that javac
 * attributes through the standard pipeline, arbitrary Java expressions are
 * supported: method calls ({@code UUID.randomUUID()}), constructor calls
 * ({@code new ArrayList<>()}), factory methods ({@code List.of(...)}), field
 * accesses, ternaries, and so on.
 */
final class RetainedInitFactory {

    private final MutationContext ctx;
    private final TreeMaker make;
    private final Names names;
    private final JavacTypeFactory types;
    private final Messager messager;
    private final boolean generatedConstructorAssigns;
    private final boolean onlyBuilderConstructor;

    /**
     * Creates the factory for a target, knowing whether the pipeline appends a
     * constructor that assigns the builder's fields.
     *
     * @param ctx the per-target mutation context
     * @param messager the processor's messager
     * @param generatedConstructorAssigns whether a constructor the pipeline appends - the all-args one or a
     *     chain's copy constructor - assigns every field the builder selects; with no author constructor,
     *     every {@code final} initializer stays on its field where none does
     * @param onlyBuilderConstructor whether the constructor the builder calls is the target's only one,
     *     which drops every instance default's initializer from its field
     */
    RetainedInitFactory(MutationContext ctx, Messager messager, boolean generatedConstructorAssigns,
                        boolean onlyBuilderConstructor) {
        this.ctx = ctx;
        this.make = ctx.make();
        this.names = ctx.names();
        this.types = ctx.types();
        this.messager = messager;
        this.generatedConstructorAssigns = generatedConstructorAssigns;
        this.onlyBuilderConstructor = onlyBuilderConstructor;
    }

    /** The convention-named static provider for a field's retained initializer. */
    static String providerName(String fieldName) {
        return "$default$" + fieldName;
    }

    /** The convention-named merge helper for a collected instance default. */
    static String mergeName(String fieldName) {
        return "$merge$" + fieldName;
    }

    /** The convention-named empty-container factory for a collected custom container. */
    static String emptyName(String fieldName) {
        return "$empty$" + fieldName;
    }

    /**
     * For every field whose initializer tree was captured by
     * {@link SourceIntrospector}, appends a provider
     * method to the target class. A tree is captured either for
     * a retained initializer or for a {@code @Collector} on a
     * custom (non-java.util) container, which needs the field's own factory to
     * build fresh instances. Fields without a captured tree (text-only, record
     * components, etc.) are skipped - the Builder falls back to its per-type
     * default.
     */
    void appendAll() {
        JCClassDecl target = ctx.target();
        java.util.List<BlankFinalLift.Writes> authored = new ArrayList<>(authorConstructors(target));
        authored.addAll(BlankFinalLift.lombokConstructors(annotationNames(), !authored.isEmpty()));
        for (FieldSpec f : ctx.fields()) {
            JCExpression original =
                f.sourceInitializerTree instanceof JCExpression captured ? captured : null;
            if (original == null && f.defaultProvider == null) {
                // A field that asked for retention by name but has nothing to
                // retain would otherwise be silently inert. Fields that merely
                // inherited the class-wide retainInit policy stay quiet - most
                // of a class's fields have no initializer and that is normal.
                if (f.builderDefaultExplicit && f.element != null) {
                    messager.printMessage(Diagnostic.Kind.WARNING,
                        "@BuilderDefault has no effect on '" + f.name
                            + "' - the field declares no initializer to retain, and names no "
                            + "provider to supply one",
                        f.element
                    );
                }
            } else if (!hasExistingProvider(target, providerName(f.name))) {
                // A written provider is the more specific statement and wins
                // over an initializer beside it.
                JCMethodDecl provider = f.defaultProvider != null
                    ? buildDelegatingProvider(f)
                    : buildProvider(f, original);
                if (provider != null) ctx.bridge().compat().appendDef(target, provider);
                // A collected instance default also needs the merge helper that
                // folds the builder's contributions onto the computed default.
                if (ctx.isCollectedInstanceDefault(f) && !hasExistingProvider(target, mergeName(f.name))) {
                    ctx.bridge().compat().appendDef(target, buildMerge(f));
                }
                // A custom container's initializer is the field's factory as
                // well as its default, and the two want different things once
                // it carries contents. Split them here.
                if (MutationContext.isCollected(f) && f.isCustomContainer
                    && !ctx.isCollectedInstanceDefault(f)
                    && !hasExistingProvider(target, emptyName(f.name))) {
                    ctx.bridge().compat().appendDef(target, buildEmptyFactory(f));
                }
            }
            // Blank-final lift: a final field carrying both an initializer AND
            // the builder-called constructor's `this.<name> = <name>` assignment
            // is doubly defined and javac rejects it ("cannot assign a value to
            // final variable"). Stripping the initializer makes the constructor
            // assignment the sole definite assignment. Non-final fields keep
            // their (dead but legal) initializer, matching prior behaviour.
            //
            // Runs for every field, not only those whose initializer was
            // retained: the constructor assigns all of them either way, so
            // turning retention off (@BuilderDefault(false), or a class-level
            // retainInit = false) must not leave a final field's initializer
            // in place. Such a field simply defaults to null, exactly as the
            // non-final case already did.
            //
            // A constructor the author wrote answers for itself: where one
            // assigns the field nowhere the initializer stays, since lifting
            // it would leave that constructor a blank final it never assigns.
            // With none written, the field is lifted only where a constructor
            // this pipeline appends assigns it - never under a factoryMethod
            // or beside an author's own build().
            if (BlankFinalLift.lifts(f.name, authored, generatedConstructorAssigns))
                stripToBlankFinal(target, f.name);
            // An instance default is computed by the builder's constructor, and
            // where that constructor is the only one its initializer is dead
            // code - but code that runs, ahead of the constructor body and so
            // ahead of every @Lazy holder, which a default reading a lazy field
            // dereferences. A non-final field would otherwise keep it.
            if (onlyBuilderConstructor && ctx.isInstanceDefault(f.name))
                dropInitializer(target, f.name);
        }
    }

    /**
     * Removes a non-{@code final} field's declared initializer, leaving a
     * {@code final} one to {@link #stripToBlankFinal}.
     *
     * @param target the target's tree
     * @param fieldName the field whose initializer the constructor recomputes
     */
    private static void dropInitializer(JCClassDecl target, String fieldName) {
        for (JCTree def : target.defs) {
            if (!(def instanceof JCVariableDecl decl)) continue;
            if (!decl.name.toString().equals(fieldName)) continue;
            if ((decl.mods.flags & Flags.FINAL) == 0) decl.init = null;
            return;
        }
    }

    /**
     * Summarises each constructor the author wrote on the target for
     * {@link BlankFinalLift}.
     *
     * <p>javac's implicit default and every constructor this pipeline generated
     * are left out: the constructor {@code build()} reaches assigns every field
     * it is built over, which the lift is told apart from these, one an args
     * annotation generates is given the initializer of each field lifted, and
     * the implicit default stands only where no constructor is written.
     *
     * @param target the target's class declaration
     * @return one summary per author-written constructor
     */
    private static java.util.List<BlankFinalLift.Writes> authorConstructors(JCClassDecl target) {
        java.util.List<BlankFinalLift.Writes> out = new ArrayList<>();
        for (JCTree def : target.defs) {
            if (!(def instanceof JCMethodDecl m)) continue;
            if (!m.name.toString().equals("<init>") || m.body == null) continue;
            if ((m.mods.flags & Flags.GENERATEDCONSTR) != 0) continue;
            if (AstMarkers.isGenerated(m)) continue;
            out.add(writesOf(m));
        }
        return out;
    }

    /**
     * The qualified names of the annotations written on the target, which
     * {@link BlankFinalLift#lombokConstructors} reads Lombok's from.
     *
     * @return the names
     */
    private java.util.List<String> annotationNames() {
        java.util.List<String> out = new ArrayList<>();
        for (AnnotationMirror mirror : ctx.targetElement().getAnnotationMirrors())
            out.add(mirror.getAnnotationType().toString());
        return out;
    }

    /**
     * Reads the statements one constructor body writes through and the names
     * it declares, never descending into a nested class's body.
     *
     * <p>The body is read into {@link BlankFinalLift}'s statement and
     * expression shapes, every statement form by its own shape and every
     * expression by the operands it evaluates. Every name the body declares is
     * read, at any depth.
     *
     * @param constructor the constructor
     * @return its summary
     */
    private static BlankFinalLift.Writes writesOf(JCMethodDecl constructor) {
        Set<String> declared = new HashSet<>();
        for (JCVariableDecl param : constructor.params) declared.add(param.name.toString());
        new TreeScanner() {
            @Override
            public void visitClassDef(JCClassDecl tree) {
            }

            @Override
            public void visitVarDef(JCVariableDecl tree) {
                declared.add(tree.name.toString());
                super.visitVarDef(tree);
            }
        }.scan(constructor.body);
        return BlankFinalLift.Writes.of(callsThis(constructor.body), declared, statementsOf(constructor.body.stats));
    }

    /**
     * Reads a statement list into the rule's shapes.
     *
     * @param statements the statements
     * @return one shape per statement
     */
    private static java.util.List<BlankFinalLift.Statement<JCTree>> statementsOf(List<? extends JCTree> statements) {
        java.util.List<BlankFinalLift.Statement<JCTree>> out = new ArrayList<>();
        for (JCTree statement : statements) out.add(statementOf(statement));
        return out;
    }

    /**
     * Reads one statement, or a {@code try} statement's resource, into the rule's shapes.
     *
     * @param statement the statement, or {@code null} where the source leaves one out
     * @return its shape
     */
    private static BlankFinalLift.Statement<JCTree> statementOf(JCTree statement) {
        if (statement instanceof JCExpressionStatement expression)
            return new BlankFinalLift.Expression<>(valueOf(expression.expr));
        if (statement instanceof JCVariableDecl variable) return new BlankFinalLift.Expression<>(valueOf(variable.init));
        if (statement instanceof JCExpression resource) return new BlankFinalLift.Expression<>(valueOf(resource));
        if (statement instanceof JCBlock block) return new BlankFinalLift.Block<>(statementsOf(block.stats));
        if (statement instanceof JCIf branch) {
            return new BlankFinalLift.Branch<>(valueOf(branch.cond), statementOf(branch.thenpart),
                branch.elsepart == null ? null : statementOf(branch.elsepart));
        }
        if (statement instanceof JCWhileLoop loop)
            return loop(BlankFinalLift.LoopKind.WHILE, List.nil(), loop.cond, List.nil(), loop.body);
        if (statement instanceof JCDoWhileLoop loop)
            return loop(BlankFinalLift.LoopKind.DO, List.nil(), loop.cond, List.nil(), loop.body);
        if (statement instanceof JCForLoop loop)
            return loop(BlankFinalLift.LoopKind.FOR, loop.init, loop.cond, loop.step, loop.body);
        if (statement instanceof JCEnhancedForLoop loop)
            return loop(BlankFinalLift.LoopKind.FOREACH, List.of(loop.expr), null, List.nil(), loop.body);
        if (statement instanceof JCSwitch choice)
            return new BlankFinalLift.Switch<>(valueOf(choice.selector), exhaustive(choice.cases), armsOf(choice.cases));
        if (statement instanceof JCTry attempt) {
            java.util.List<BlankFinalLift.Statement<JCTree>> body = statementsOf(attempt.resources);
            body.addAll(statementsOf(attempt.body.stats));
            java.util.List<java.util.List<BlankFinalLift.Statement<JCTree>>> catches = new ArrayList<>();
            for (JCCatch handler : attempt.catchers) catches.add(statementsOf(handler.body.stats));
            return new BlankFinalLift.Try<>(body, catches,
                attempt.finalizer == null ? null : statementsOf(attempt.finalizer.stats));
        }
        if (statement instanceof JCSynchronized lock) {
            return new BlankFinalLift.Block<>(java.util.List.of(
                new BlankFinalLift.Expression<>(valueOf(lock.lock)), statementOf(lock.body)));
        }
        if (statement instanceof JCLabeledStatement labelled)
            return new BlankFinalLift.Labelled<>(labelled.label.toString(), statementOf(labelled.body));
        if (statement instanceof JCReturn)
            return new BlankFinalLift.Jump<>(BlankFinalLift.JumpKind.RETURN, null, null);
        if (statement instanceof JCThrow thrown)
            return new BlankFinalLift.Jump<>(BlankFinalLift.JumpKind.THROW, null, valueOf(thrown.expr));
        if (statement instanceof JCBreak jump) {
            return new BlankFinalLift.Jump<>(BlankFinalLift.JumpKind.BREAK,
                jump.label == null ? null : jump.label.toString(), null);
        }
        if (statement instanceof JCContinue jump) {
            return new BlankFinalLift.Jump<>(BlankFinalLift.JumpKind.CONTINUE,
                jump.label == null ? null : jump.label.toString(), null);
        }
        if (statement instanceof JCYield yielded)
            return new BlankFinalLift.Jump<>(BlankFinalLift.JumpKind.YIELD, null, valueOf(yielded.value));
        if (statement instanceof JCAssert check) {
            return new BlankFinalLift.Assert<>(valueOf(check.cond),
                check.detail == null ? null : valueOf(check.detail));
        }
        return new BlankFinalLift.Block<>(java.util.List.of());
    }

    /**
     * Reads a loop into the rule's shape.
     *
     * @param kind which loop it is
     * @param init what runs once ahead of it
     * @param condition the condition, or {@code null}
     * @param update the update statements
     * @param body the body
     * @return its shape
     */
    private static BlankFinalLift.Statement<JCTree> loop(BlankFinalLift.LoopKind kind, List<? extends JCTree> init,
                                                         JCExpression condition, List<? extends JCTree> update,
                                                         JCStatement body) {
        return new BlankFinalLift.Loop<>(kind, statementsOf(init), condition == null ? null : valueOf(condition),
            statementsOf(update), statementOf(body));
    }

    /**
     * Reads an expression into the rule's shapes, by the operands it
     * evaluates and the names it writes.
     *
     * @param expression the expression, or {@code null} where the source leaves one out
     * @return its shape
     */
    private static BlankFinalLift.Value<JCTree> valueOf(JCExpression expression) {
        if (expression == null) return new BlankFinalLift.Evaluate<>(java.util.List.of());
        JCExpression tree = TreeInfo.skipParens(expression);
        if (tree instanceof JCLiteral literal && literal.typetag == TypeTag.BOOLEAN)
            return new BlankFinalLift.Constant<>(Boolean.TRUE.equals(literal.getValue()));
        if (tree instanceof JCAssign assign)
            return new BlankFinalLift.Assign<>(java.util.List.of(valueOf(assign.lhs), valueOf(assign.rhs)), writeOf(assign.lhs));
        if (tree instanceof JCAssignOp assign)
            return new BlankFinalLift.Assign<>(java.util.List.of(valueOf(assign.lhs), valueOf(assign.rhs)), writeOf(assign.lhs));
        if (tree instanceof JCUnary unary) {
            if (unary.hasTag(JCTree.Tag.NOT)) return new BlankFinalLift.Not<>(valueOf(unary.arg));
            if (unary.hasTag(JCTree.Tag.PREINC) || unary.hasTag(JCTree.Tag.PREDEC)
                || unary.hasTag(JCTree.Tag.POSTINC) || unary.hasTag(JCTree.Tag.POSTDEC))
                return new BlankFinalLift.Assign<>(java.util.List.of(valueOf(unary.arg)), writeOf(unary.arg));
        }
        if (tree instanceof JCBinary binary && binary.hasTag(JCTree.Tag.AND))
            return new BlankFinalLift.And<>(valueOf(binary.lhs), valueOf(binary.rhs));
        if (tree instanceof JCBinary binary && binary.hasTag(JCTree.Tag.OR))
            return new BlankFinalLift.Or<>(valueOf(binary.lhs), valueOf(binary.rhs));
        if (tree instanceof JCConditional choice) {
            return new BlankFinalLift.Choice<>(valueOf(choice.cond), valueOf(choice.truepart),
                valueOf(choice.falsepart));
        }
        if (tree instanceof JCSwitchExpression choice)
            return new BlankFinalLift.SwitchValue<>(valueOf(choice.selector), armsOf(choice.cases));
        if (tree instanceof JCLambda || tree instanceof JCAnnotation)
            return new BlankFinalLift.Evaluate<>(java.util.List.of());
        return new BlankFinalLift.Evaluate<>(operandsOf(tree));
    }

    /**
     * Reads the operands an expression evaluates, in order, leaving out a
     * nested class's body.
     *
     * @param expression the expression
     * @return one shape per operand
     */
    private static java.util.List<BlankFinalLift.Value<JCTree>> operandsOf(JCExpression expression) {
        java.util.List<BlankFinalLift.Value<JCTree>> out = new ArrayList<>();
        new TreeScanner() {
            private boolean entered;

            @Override
            public void scan(JCTree child) {
                if (child == null) return;
                if (!entered) {
                    entered = true;
                    child.accept(this);
                } else if (child instanceof JCExpression operand) {
                    out.add(valueOf(operand));
                }
            }
        }.scan(expression);
        return out;
    }

    /**
     * Reads the target of a write, kept where it is written through its bare
     * name or an unqualified {@code this}.
     *
     * @param target the written expression
     * @return the write, or {@code null} for any other target
     */
    private static BlankFinalLift.Write<JCTree> writeOf(JCExpression target) {
        JCExpression written = TreeInfo.skipParens(target);
        if (written instanceof JCIdent bare) return new BlankFinalLift.Write<>(bare.name.toString(), false, written);
        if (written instanceof JCFieldAccess select
            && TreeInfo.skipParens(select.selected) instanceof JCIdent qualifier
            && qualifier.name.toString().equals("this"))
            return new BlankFinalLift.Write<>(select.name.toString(), true, written);
        return null;
    }

    /**
     * Reads a {@code switch}'s cases into its arms, a colon label with no
     * statements of its own joining the arm after it.
     *
     * @param cases the cases
     * @return the arms
     */
    private static java.util.List<BlankFinalLift.Arm<JCTree>> armsOf(List<JCCase> cases) {
        java.util.List<BlankFinalLift.Arm<JCTree>> arms = new ArrayList<>();
        for (List<JCCase> rest = cases; rest.nonEmpty(); rest = rest.tail) {
            JCCase arm = rest.head;
            boolean rule = arm.caseKind == CaseTree.CaseKind.RULE;
            if (!rule && arm.stats.isEmpty() && rest.tail.nonEmpty()) continue;
            arms.add(new BlankFinalLift.Arm<>(rule, statementsOf(arm.stats)));
        }
        return arms;
    }

    /**
     * Whether a {@code switch} statement's labels make javac require it to
     * cover every value - a {@code default}, a pattern or a {@code null} label.
     *
     * <p>Read by the tree's tag names, which differ between the JDKs the
     * processor runs on.
     *
     * @param cases the cases
     * @return whether it is exhaustive
     */
    private static boolean exhaustive(List<JCCase> cases) {
        for (JCCase arm : cases) {
            for (JCTree label : arm.labels) {
                String tag = label.getTag().name();
                if (label.hasTag(JCTree.Tag.DEFAULTCASELABEL) || tag.endsWith("PATTERN")
                    || tag.equals("PATTERNCASELABEL")) return true;
            }
            for (JCExpression constant : arm.getExpressions()) {
                if (TreeInfo.skipParens(constant) instanceof JCLiteral literal && literal.typetag == TypeTag.BOT)
                    return true;
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
    private static boolean callsThis(JCBlock body) {
        for (JCStatement statement : body.stats) {
            if (statement instanceof JCExpressionStatement expression
                && expression.expr instanceof JCMethodInvocation call
                && call.meth instanceof JCIdent callee
                && callee.name.toString().equals("this")) return true;
        }
        return false;
    }

    /**
     * Removes a {@code final} field's declared initializer so the
     * builder-populated constructor can assign it. Nulls the tree initializer -
     * which survives javac's re-{@code MemberEnter} passes under multi-round
     * (Lombok-co-resident) processing, so the field symbol is re-derived as a
     * genuine blank final - and clears {@code HASINIT} on the field symbol for
     * the single-round case where no re-enter happens. Idempotent across rounds
     * (an already-blank field is left alone).
     *
     * <p>A constructor an args annotation generated beside the builder's
     * assigns none of the fields the lift takes an initializer off, and would
     * leave the blank final unassigned. Each such constructor is given the
     * initializer as its first statement, so an instance built through it holds
     * the value the initializer gave it, as one built through an author
     * constructor leaving the field does.
     */
    private void stripToBlankFinal(JCClassDecl target, String fieldName) {
        for (JCTree def : target.defs) {
            if (!(def instanceof JCVariableDecl decl)) continue;
            if (!decl.name.toString().equals(fieldName)) continue;
            // Read finality off the tree rather than FieldSpec.isFinal: @Lazy
            // adds Flags.FINAL during its own earlier pass, so the FieldSpec
            // snapshot under-reports it and a @Lazy field would keep both its
            // initializer and the constructor's assignment.
            if ((decl.mods.flags & Flags.FINAL) == 0) return;
            if (decl.init == null) return; // already blank (re-run idempotency)
            carryIntoGeneratedConstructors(target, fieldName, decl.init);
            decl.init = null;
            if (decl.sym != null) decl.sym.flags_field &= ~Flags.HASINIT;
            return;
        }
    }

    /**
     * Assigns a lifted field its initializer in every generated constructor
     * that neither assigns the field nor delegates to another constructor.
     *
     * @param target the target's tree
     * @param fieldName the lifted field
     * @param initializer the initializer taken off it
     */
    private void carryIntoGeneratedConstructors(JCClassDecl target, String fieldName, JCExpression initializer) {
        for (JCTree def : target.defs) {
            if (!(def instanceof JCMethodDecl m)) continue;
            if (!m.name.toString().equals("<init>") || m.body == null || !AstMarkers.isGenerated(m)) continue;
            BlankFinalLift.Writes writes = writesOf(m);
            if (writes.delegates() || writes.assigned().contains(fieldName)) continue;
            JCStatement assign = make.Exec(make.Assign(
                make.Select(make.Ident(names._this), names.fromString(fieldName)),
                new ResettingCopier(make).copy(initializer)));
            m.body.stats = m.body.stats.prepend(assign);
        }
    }

    /**
     * Builds {@code private static T $default$<fieldName>() { return <cloned>; }}
     * for a single field. The cloned tree has every {@code sym}, {@code type},
     * and {@code pos} field stripped so javac re-resolves the expression
     * against the method body's scope during its normal Attr pass.
     */
    private JCMethodDecl buildProvider(FieldSpec field, JCExpression original) {
        JCExpression cleaned = new ResettingCopier(make).copy(original);
        if (cleaned == null) return null;
        JCStatement returnStmt = make.Return(cleaned);
        JCBlock body = make.Block(0, List.of(returnStmt));
        JCExpression returnType = types.parseType(field.typeDisplay);
        // An initializer reading instance state cannot be evaluated when the
        // builder is created, since no target exists then. Its provider is an
        // instance method, called from the generated constructor where `this`
        // is available - the same place an ordinary field initializer runs.
        boolean instance = ctx.isInstanceDefault(field.name);
        long modifiers = instance ? Flags.PRIVATE : Flags.PRIVATE | Flags.STATIC;
        // A static provider on a generic target cannot see the class's type
        // variables, so it re-declares them; the call site infers them back
        // from the builder slot's own type. The instance form needs none -
        // it runs with the class's parameters already in scope.
        JCMethodDecl method = make.MethodDef(
            make.Modifiers(modifiers),
            names.fromString(providerName(field.name)),
            returnType,
            instance ? List.nil() : ctx.typeParams(),
            List.nil(),
            List.nil(),
            body,
            null
        );
        AstMarkers.markGenerated(method, ctx.generated());
        return method;
    }

    /**
     * Builds {@code private static T $default$<fieldName>() { return <provider>(); }}
     * for a field naming a provider rather than retaining an initializer.
     *
     * <p>Routed through the same {@code $default$} name the captured-expression
     * form uses, so nothing downstream has to know which it was: the builder
     * slot, the merge helper, the empty-container factory and the collected
     * defaults all call one method. The provider is checked at the annotation
     * before this runs, so the call it emits resolves.
     *
     * <p>Nothing here is instance-computed. A provider is required to be
     * {@code static}, which is what makes the value available when the builder
     * is created rather than only once a target exists.
     */
    private JCMethodDecl buildDelegatingProvider(FieldSpec field) {
        JCExpression call = make.Apply(
            List.nil(), make.Ident(names.fromString(field.defaultProvider)), List.nil());
        JCBlock body = make.Block(0, List.of(make.Return(call)));
        JCMethodDecl method = make.MethodDef(
            make.Modifiers(Flags.PRIVATE | Flags.STATIC),
            names.fromString(providerName(field.name)),
            types.parseType(field.typeDisplay),
            ctx.typeParams(),
            List.nil(),
            List.nil(),
            body,
            null
        );
        AstMarkers.markGenerated(method, ctx.generated());
        return method;
    }

    /**
     * Builds the merge helper for a {@code @Collector} field whose default reads
     * instance state:
     *
     * <pre>{@code
     * private List<String> $merge$items(List<String> contributed, boolean replaced) {
     *     if (replaced) return contributed;
     *     List<String> base = $default$items();
     *     base.addAll(contributed);
     *     return base;
     * }
     * }</pre>
     *
     * <p>The builder cannot seed its slot from an instance-reading default -
     * no target exists when the builder is created - so the slot carries only
     * what the caller contributed and the fold happens here, where {@code this}
     * is available. An untouched builder contributes an empty collection, which
     * makes the untouched and appended-to cases the same code path; only a
     * wholesale replace ({@code items(...)}, {@code clearItems()}) has to
     * discard the default, which is what {@code replaced} records.
     */
    private JCMethodDecl buildMerge(FieldSpec field) {
        JCExpression fieldType = types.parseType(field.typeDisplay);
        Name contributed = names.fromString("contributed");
        Name replaced = names.fromString("replaced");
        Name base = names.fromString("base");

        // The container always comes from the field's own initializer, even
        // when the caller replaced its contents - so the built object holds
        // exactly what the initializer returns, subclass and all, and nothing
        // has to construct the declared type.
        JCStatement declareBase = make.VarDef(
            make.Modifiers(0), base, types.parseType(field.typeDisplay),
            make.Apply(List.nil(), make.Ident(names.fromString(providerName(field.name))), List.nil()));
        JCStatement discardDefault = make.If(
            make.Ident(replaced),
            make.Exec(make.Apply(List.nil(),
                make.Select(make.Ident(base), names.fromString("clear")), List.nil())),
            null);
        // addAll for a collection, putAll for a map.
        JCStatement fold = make.Exec(make.Apply(
            List.nil(),
            make.Select(make.Ident(base), names.fromString(field.isMap ? "putAll" : "addAll")),
            List.of(make.Ident(contributed))
        ));
        JCBlock body = make.Block(0,
            List.of(declareBase, discardDefault, fold, make.Return(make.Ident(base))));

        JCMethodDecl method = make.MethodDef(
            make.Modifiers(Flags.PRIVATE),
            names.fromString(mergeName(field.name)),
            fieldType,
            List.nil(),
            List.of(
                make.VarDef(make.Modifiers(Flags.PARAMETER), contributed, ctx.collectedSlotType(field), null),
                make.VarDef(make.Modifiers(Flags.PARAMETER), replaced,
                    make.TypeIdent(com.sun.tools.javac.code.TypeTag.BOOLEAN), null)
            ),
            List.nil(),
            body,
            null
        );
        AstMarkers.markGenerated(method, ctx.generated());
        return method;
    }

    /**
     * Builds the empty-container factory for a {@code @Collector} on a custom
     * container:
     *
     * <pre>{@code
     * private static ConcurrentList<String> $empty$items() {
     *     ConcurrentList<String> fresh = $default$items();
     *     fresh.clear();
     *     return fresh;
     * }
     * }</pre>
     *
     * <p>A {@code java.util} container has a universal way to make a fresh
     * empty one - {@code new ArrayList<>()} and friends - so its default and
     * its factory are independent. A custom container has no such expression:
     * the only thing that can produce one is the field's own initializer. That
     * makes the initializer serve both roles, and the roles disagree the moment
     * it carries contents - a replace setter resetting through it would keep
     * the default's elements instead of discarding them. Emptying a fresh
     * instance separates the two.
     */
    private JCMethodDecl buildEmptyFactory(FieldSpec field) {
        Name fresh = names.fromString("fresh");
        JCStatement declare = make.VarDef(
            make.Modifiers(0), fresh, types.parseType(field.typeDisplay),
            make.Apply(List.nil(), make.Ident(names.fromString(providerName(field.name))), List.nil()));
        JCStatement clear = make.Exec(make.Apply(
            List.nil(),
            make.Select(make.Ident(fresh), names.fromString("clear")),
            List.nil()
        ));
        JCBlock body = make.Block(0, List.of(declare, clear, make.Return(make.Ident(fresh))));
        JCMethodDecl method = make.MethodDef(
            make.Modifiers(Flags.PRIVATE | Flags.STATIC),
            names.fromString(emptyName(field.name)),
            types.parseType(field.typeDisplay),
            ctx.typeParams(),
            List.nil(),
            List.nil(),
            body,
            null
        );
        AstMarkers.markGenerated(method, ctx.generated());
        return method;
    }

    /** Idempotency guard for re-entrant annotation processing rounds. */
    private static boolean hasExistingProvider(JCClassDecl target, String name) {
        for (var def : target.defs) {
            if (def instanceof JCMethodDecl m && m.name.toString().equals(name)) return true;
        }
        return false;
    }

    /**
     * {@link TreeCopier} that resets every per-node symbol / type / position
     * so javac treats the copy as a freshly-parsed expression. Without this
     * reset the Builder's new method body inherits attribution pointers from
     * the original field's scope, which javac's type-checker then NPEs on
     * when it tries to reconcile them against the method's own scope.
     *
     * <p>{@link JCVariableDecl} covers a lambda's parameters and any locals in
     * its block body. Their {@code VarSymbol}s carry a definite-assignment
     * address allocated in the field initializer's scope; left in place,
     * {@code Flow$AssignAnalyzer.visitLambda} feeds that stale address to
     * {@code Bits.incl} and javac dies on an assertion with no diagnostic.
     * Nulling the symbol makes the provider method's own scope allocate fresh
     * addresses, which is the same reason the expression is moved into a
     * method body rather than embedded in a field initializer.
     */
    private static final class ResettingCopier extends TreeCopier<Void> {

        private final int pos;

        ResettingCopier(TreeMaker maker) {
            super(maker);
            this.pos = maker.pos;
        }

        @Override
        public <T extends JCTree> T copy(T tree, Void unused) {
            T copy = super.copy(tree, unused);
            if (copy != null) {
                copy.pos = pos;
                copy.type = null;
                if (copy instanceof JCIdent id) id.sym = null;
                else if (copy instanceof JCFieldAccess fa) fa.sym = null;
                else if (copy instanceof JCMethodInvocation mi) mi.polyKind = null;
                else if (copy instanceof JCVariableDecl vd) vd.sym = null;
                else if (copy instanceof JCNewClass nc) {
                    nc.constructor = null;
                    nc.constructorType = null;
                    nc.varargsElement = null;
                }
            }
            return copy;
        }
    }

}
