package dev.simplified.classbuilder.mutate;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;
import dev.simplified.classbuilder.apt.FlaggedMember;
import dev.simplified.shared.javac.JavacTypeFactory;

/**
 * Emits the {@code @BuildFlag} enforcement a generated {@code build()} runs
 * against the object it just constructed.
 *
 * <p>The checks are written into the builder itself rather than delegated to a
 * runtime helper, so a target carrying constraints compiles to code that needs
 * nothing on the classpath but the JDK. Constraint values are known at
 * annotation-processing time, so every field name, type name, bound and pattern
 * is a literal and the only work left at runtime is reading the value.
 *
 * <p>Values are inspected through generated {@code Object}-taking helpers rather
 * than through checks specialised per field type. One shape covers a field, an
 * inherited field and an interface accessor alike, and it is the same shape
 * whatever the declared type is - which is what keeps a constraint from
 * quietly not being enforced because its type was not one the emitter
 * recognised.
 */
final class BuildFlagChecks {

    static final String VALIDATE = "$validate$";
    private static final String EMPTY = "$flagEmpty$";
    private static final String SIZE = "$flagSize$";
    private static final String TEXT = "$flagText$";
    private static final String NUMBER = "$flagNumber$";

    private static final String RESULT = "$result";
    private static final String VALUE = "$v";

    private final MutationContext ctx;
    private final TreeMaker make;
    private final Names names;
    private final JavacTypeFactory types;
    private final java.util.List<FlaggedMember> flagged;

    BuildFlagChecks(MutationContext ctx, java.util.List<FlaggedMember> flagged) {
        this.ctx = ctx;
        this.make = ctx.make();
        this.names = ctx.names();
        this.types = ctx.types();
        this.flagged = flagged;
    }

    /** Whether anything is actually constrained, and so whether to emit at all. */
    boolean enabled() {
        return !flagged.isEmpty();
    }

    /** {@code $validate$($result);} */
    JCStatement call() {
        return make.Exec(make.Apply(
            List.nil(),
            make.Ident(names.fromString(VALIDATE)),
            List.of(make.Ident(names.fromString(RESULT)))
        ));
    }

    /**
     * The members {@code build()} needs: the validator itself plus the value
     * helpers it calls.
     *
     * @return the generated members, empty when nothing is constrained
     */
    List<JCTree> members() {
        if (!enabled()) return List.nil();
        return List.of(
            validateMethod(),
            emptyHelper(),
            sizeHelper(),
            textHelper(),
            numberHelper()
        );
    }

    // ------------------------------------------------------------------
    // The validator
    // ------------------------------------------------------------------

    /**
     * {@code private static void $validate$(Target $result)} - every constraint
     * on the constructed object, in declaration order, with grouped
     * requirements resolved last because a group only fails once every member
     * of it has been found wanting.
     */
    private JCMethodDecl validateMethod() {
        ListBuffer<JCStatement> body = new ListBuffer<>();
        java.util.Map<String, java.util.List<FlaggedMember>> groups = new java.util.LinkedHashMap<>();

        for (FlaggedMember member : flagged) {
            if (member.flag().nonNull() || member.flag().notEmpty()) {
                if (member.flag().group().length == 0) {
                    JCExpression invalid = requiredInvalid(member);
                    if (invalid != null) {
                        body.append(make.If(invalid, throwing(
                            "Field '" + member.name() + "' in '" + ctx.targetSimpleName()
                                + "' is required and is null/empty"), null));
                    }
                } else {
                    for (String group : member.flag().group()) {
                        groups.computeIfAbsent(group, g -> new java.util.ArrayList<>()).add(member);
                    }
                }
            }
            patternCheck(member, body);
            limitCheck(member, body);
            boundsCheck(member, body);
        }

        for (java.util.Map.Entry<String, java.util.List<FlaggedMember>> entry : groups.entrySet()) {
            JCStatement check = groupCheck(entry.getKey(), entry.getValue());
            if (check != null) body.append(check);
        }

        JCVariableDecl param = make.VarDef(
            make.Modifiers(Flags.PARAMETER),
            names.fromString(RESULT),
            ctx.builtType(),
            null
        );
        return make.MethodDef(
            make.Modifiers(Flags.PRIVATE | Flags.STATIC),
            names.fromString(VALIDATE),
            types.parseType("void"),
            List.nil(),
            List.of(param),
            List.nil(),
            make.Block(0, body.toList()),
            null
        );
    }

    /**
     * The condition under which a {@code nonNull} or {@code notEmpty} member is
     * unsatisfied, or {@code null} when it cannot be.
     *
     * <p>{@code notEmpty} subsumes {@code nonNull}, because emptiness counts an
     * absent value as empty. A primitive can be neither, so it yields no test
     * rather than an {@code int == null} the target would not compile.
     */
    private JCExpression requiredInvalid(FlaggedMember member) {
        if (member.primitive()) return null;
        if (member.flag().notEmpty()) {
            return make.Apply(List.nil(), make.Ident(names.fromString(EMPTY)), List.of(read(member)));
        }
        return isNull(read(member));
    }

    /** {@code if (a && b && ...) throw ...} - a group fails only when every member does. */
    private JCStatement groupCheck(String group, java.util.List<FlaggedMember> members) {
        JCExpression all = null;
        StringBuilder missing = new StringBuilder();
        for (FlaggedMember member : members) {
            JCExpression invalid = requiredInvalid(member);
            // A member that cannot be unsatisfied satisfies the group outright,
            // so the whole check goes rather than silently dropping that member
            // and letting the others fail on its behalf.
            if (invalid == null) return null;
            all = all == null ? invalid : make.Binary(JCTree.Tag.AND, all, invalid);
            if (missing.length() > 0) missing.append(',');
            missing.append(member.name());
        }
        if (all == null) return null;
        return make.If(all, throwing("Field group '" + group + "' in '" + ctx.targetSimpleName()
            + "' is required and [" + missing + "] is null/empty"), null);
    }

    /** {@code String $t = $flagText$(v); if ($t != null && !Pattern.matches(p, $t)) throw ...} */
    private void patternCheck(FlaggedMember member, ListBuffer<JCStatement> body) {
        String pattern = member.flag().pattern();
        if (pattern.isEmpty()) return;
        String local = "$t$" + member.name();
        body.append(make.VarDef(
            make.Modifiers(0),
            names.fromString(local),
            types.parseType("java.lang.String"),
            make.Apply(List.nil(), make.Ident(names.fromString(TEXT)), List.of(read(member)))
        ));
        JCExpression text = make.Ident(names.fromString(local));
        JCExpression matches = make.Apply(
            List.nil(),
            make.Select(types.qualIdent("java.util.regex.Pattern"), names.fromString("matches")),
            List.of(make.Literal(pattern), text)
        );
        JCExpression violated = make.Binary(JCTree.Tag.AND, isNotNull(text), make.Unary(JCTree.Tag.NOT, matches));
        body.append(make.If(violated, throwingWith(
            "Field '" + member.name() + "' in '" + ctx.targetSimpleName()
                + "' does not match pattern '" + pattern + "' (value: '",
            read(member), "')"), null));
    }

    /** {@code int $n = $flagSize$(v); if ($n > limit) throw ...} */
    private void limitCheck(FlaggedMember member, ListBuffer<JCStatement> body) {
        int limit = member.flag().limit();
        if (limit < 0) return;
        String local = "$n$" + member.name();
        body.append(make.VarDef(
            make.Modifiers(0),
            names.fromString(local),
            types.parseType("int"),
            make.Apply(List.nil(), make.Ident(names.fromString(SIZE)), List.of(read(member)))
        ));
        JCExpression measured = make.Ident(names.fromString(local));
        body.append(make.If(
            make.Binary(JCTree.Tag.GT, measured, make.Literal(limit)),
            throwingWith("Field '" + member.name() + "' in '" + ctx.targetSimpleName()
                + "' has length ", measured, ", exceeds limit of " + limit),
            null));
    }

    /** {@code Number $b = $flagNumber$(v); if ($b != null && $b.doubleValue() < min) throw ...} */
    private void boundsCheck(FlaggedMember member, ListBuffer<JCStatement> body) {
        double min = member.flag().min();
        double max = member.flag().max();
        if (min == Double.NEGATIVE_INFINITY && max == Double.POSITIVE_INFINITY) return;
        String local = "$b$" + member.name();
        body.append(make.VarDef(
            make.Modifiers(0),
            names.fromString(local),
            types.parseType("java.lang.Number"),
            make.Apply(List.nil(), make.Ident(names.fromString(NUMBER)), List.of(read(member)))
        ));
        JCExpression bounded = make.Ident(names.fromString(local));
        if (min != Double.NEGATIVE_INFINITY) {
            body.append(bound(member, bounded, JCTree.Tag.LT, min, "below the minimum of "));
        }
        if (max != Double.POSITIVE_INFINITY) {
            body.append(bound(member, bounded, JCTree.Tag.GT, max, "above the maximum of "));
        }
    }

    private JCStatement bound(FlaggedMember member, JCExpression bounded,
                              JCTree.Tag comparison, double limit, String phrase) {
        JCExpression violated = make.Binary(JCTree.Tag.AND,
            isNotNull(bounded),
            make.Binary(comparison, call(bounded, "doubleValue"), make.Literal(limit)));
        return make.If(violated, throwingWith(
            "Field '" + member.name() + "' in '" + ctx.targetSimpleName() + "' is ",
            bounded, ", " + phrase + render(limit)), null);
    }

    /**
     * Renders a bound the way it was written rather than as the {@code double}
     * it is stored in, so {@code min = 0} on an {@code int} field reads as
     * {@code 0} and not {@code 0.0}.
     */
    private static String render(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) return String.valueOf((long) value);
        return String.valueOf(value);
    }

    /** {@code $result.<name>} for a field, {@code $result.<name>()} for an accessor. */
    private JCExpression read(FlaggedMember member) {
        JCExpression target = make.Ident(names.fromString(RESULT));
        return member.accessor()
            ? make.Apply(List.nil(), select(target, member.name()), List.nil())
            : select(target, member.name());
    }

    /** {@code throw new IllegalStateException("<message>");} */
    private JCStatement throwing(String message) {
        return make.Throw(make.NewClass(
            null,
            List.nil(),
            types.parseType("java.lang.IllegalStateException"),
            List.of(make.Literal(message)),
            null
        ));
    }

    /**
     * The same, with a runtime value spliced between two literals. Built by
     * concatenation rather than {@code String.format} so a constraint whose own
     * text carries a percent sign - a regex, most of all - cannot be read as a
     * format specifier.
     */
    private JCStatement throwingWith(String prefix, JCExpression value, String suffix) {
        JCExpression message = make.Binary(JCTree.Tag.PLUS,
            make.Binary(JCTree.Tag.PLUS, make.Literal(prefix), value),
            make.Literal(suffix));
        return make.Throw(make.NewClass(
            null,
            List.nil(),
            types.parseType("java.lang.IllegalStateException"),
            List.of(message),
            null
        ));
    }

    // ------------------------------------------------------------------
    // Generated value helpers
    // ------------------------------------------------------------------

    /**
     * {@code private static boolean $flagEmpty$(Object $v)} - emptiness as
     * {@code @BuildFlag} defines it, with {@code null} counted as empty so a
     * {@code notEmpty} constraint rejects an unset value without a second test.
     */
    private JCMethodDecl emptyHelper() {
        ListBuffer<JCStatement> body = new ListBuffer<>();
        body.append(make.If(isNull(value()), returnBool(true), null));
        body.append(testReturn("java.lang.CharSequence",
            v -> eqZero(call(v, "length"))));
        body.append(testReturn("java.util.Optional<?>", v -> call(v, "isEmpty")));
        body.append(testReturn("java.util.Collection<?>", v -> call(v, "isEmpty")));
        body.append(testReturn("java.util.Map<?, ?>", v -> call(v, "isEmpty")));
        body.append(arrayTestReturn(v -> eqZero(select(v, "length"))));
        body.append(returnBool(false));
        return helper(EMPTY, types.parseType("boolean"), body.toList());
    }

    /**
     * {@code private static int $flagSize$(Object $v)} - the measurement a
     * {@code limit} is compared against, or {@code -1} when the value is not
     * something a length means anything for.
     */
    private JCMethodDecl sizeHelper() {
        ListBuffer<JCStatement> body = new ListBuffer<>();
        body.append(make.If(isNull(value()), returnInt(-1), null));
        body.append(testReturn("java.lang.CharSequence", v -> call(v, "length")));
        body.append(testReturn("java.util.Collection<?>", v -> call(v, "size")));
        body.append(testReturn("java.util.Map<?, ?>", v -> call(v, "size")));
        body.append(arrayTestReturn(v -> select(v, "length")));
        body.append(make.If(
            instanceOf(value(), "java.util.Optional<?>"),
            optionalSize(),
            null));
        body.append(returnInt(-1));
        return helper(SIZE, types.parseType("int"), body.toList());
    }

    /** The {@code Optional} arm of {@link #sizeHelper}, measuring what it holds. */
    private JCBlock optionalSize() {
        String inner = "$o";
        ListBuffer<JCStatement> body = new ListBuffer<>();
        body.append(make.VarDef(
            make.Modifiers(0),
            names.fromString(inner),
            types.parseType("java.lang.Object"),
            orElseNull(cast("java.util.Optional<?>", value()))
        ));
        JCExpression o = make.Ident(names.fromString(inner));
        body.append(make.If(isNull(o), returnInt(0), null));
        body.append(make.If(instanceOf(o, "java.lang.CharSequence"),
            make.Return(call(cast("java.lang.CharSequence", o), "length")), null));
        body.append(make.If(instanceOf(o, "java.lang.Number"),
            make.Return(call(cast("java.lang.Number", o), "intValue")), null));
        body.append(make.Return(call(valueOf(o), "length")));
        return make.Block(0, body.toList());
    }

    /**
     * {@code private static String $flagText$(Object $v)} - the text a
     * {@code pattern} is matched against, or {@code null} when the value is not
     * text and the constraint therefore says nothing about it.
     */
    private JCMethodDecl textHelper() {
        ListBuffer<JCStatement> body = new ListBuffer<>();
        body.append(testReturn("java.lang.CharSequence", v -> call(v, "toString")));
        String inner = "$o";
        ListBuffer<JCStatement> arm = new ListBuffer<>();
        arm.append(make.VarDef(
            make.Modifiers(0),
            names.fromString(inner),
            types.parseType("java.lang.Object"),
            orElseNull(cast("java.util.Optional<?>", value()))
        ));
        JCExpression o = make.Ident(names.fromString(inner));
        arm.append(make.Return(make.Conditional(isNull(o), nullLiteral(), valueOf(o))));
        body.append(make.If(instanceOf(value(), "java.util.Optional<?>"),
            make.Block(0, arm.toList()), null));
        body.append(make.Return(nullLiteral()));
        return helper(TEXT, types.parseType("java.lang.String"), body.toList());
    }

    /**
     * {@code private static Number $flagNumber$(Object $v)} - the number a
     * range is compared against, or {@code null} when there is nothing to
     * bound. Returns the value itself rather than a {@code double} so a
     * rejection reports it the way it was written.
     */
    private JCMethodDecl numberHelper() {
        ListBuffer<JCStatement> body = new ListBuffer<>();
        body.append(testReturn("java.lang.Number", v -> v));
        String inner = "$o";
        ListBuffer<JCStatement> arm = new ListBuffer<>();
        arm.append(make.VarDef(
            make.Modifiers(0),
            names.fromString(inner),
            types.parseType("java.lang.Object"),
            orElseNull(cast("java.util.Optional<?>", value()))
        ));
        JCExpression o = make.Ident(names.fromString(inner));
        arm.append(make.If(instanceOf(o, "java.lang.Number"),
            make.Return(cast("java.lang.Number", o)), null));
        body.append(make.If(instanceOf(value(), "java.util.Optional<?>"),
            make.Block(0, arm.toList()), null));
        body.append(make.Return(nullLiteral()));
        return helper(NUMBER, types.parseType("java.lang.Number"), body.toList());
    }

    /** A {@code private static} helper taking the value under inspection. */
    private JCMethodDecl helper(String name, JCExpression returnType, List<JCStatement> body) {
        JCVariableDecl param = make.VarDef(
            make.Modifiers(Flags.PARAMETER),
            names.fromString(VALUE),
            types.parseType("java.lang.Object"),
            null
        );
        return make.MethodDef(
            make.Modifiers(Flags.PRIVATE | Flags.STATIC),
            names.fromString(name),
            returnType,
            List.nil(),
            List.of(param),
            List.nil(),
            make.Block(0, body),
            null
        );
    }

    // ------------------------------------------------------------------
    // Small AST spellings
    // ------------------------------------------------------------------

    private JCExpression value() {
        return make.Ident(names.fromString(VALUE));
    }

    private JCExpression nullLiteral() {
        return make.Literal(TypeTag.BOT, null);
    }

    private JCExpression isNull(JCExpression e) {
        return make.Binary(JCTree.Tag.EQ, e, nullLiteral());
    }

    private JCExpression isNotNull(JCExpression e) {
        return make.Binary(JCTree.Tag.NE, e, nullLiteral());
    }

    private JCExpression instanceOf(JCExpression e, String type) {
        return make.TypeTest(e, types.parseType(type));
    }

    private JCExpression cast(String type, JCExpression e) {
        return make.TypeCast(types.parseType(type), e);
    }

    private JCExpression call(JCExpression receiver, String method) {
        return make.Apply(List.nil(), select(receiver, method), List.nil());
    }

    private JCExpression select(JCExpression receiver, String member) {
        return make.Select(receiver, names.fromString(member));
    }

    private JCExpression orElseNull(JCExpression optional) {
        return make.Apply(List.nil(), select(optional, "orElse"), List.of(nullLiteral()));
    }

    private JCExpression valueOf(JCExpression e) {
        return make.Apply(
            List.nil(),
            make.Select(types.qualIdent("java.lang.String"), names.fromString("valueOf")),
            List.of(e)
        );
    }

    private JCExpression eqZero(JCExpression e) {
        return make.Binary(JCTree.Tag.EQ, e, make.Literal(0));
    }

    private JCStatement returnBool(boolean v) {
        return make.Return(make.Literal(v));
    }

    private JCStatement returnInt(int v) {
        return make.Return(make.Literal(v));
    }

    /** {@code if ($v instanceof T) return <fn(($v))>;} */
    private JCStatement testReturn(String type,
                                  java.util.function.Function<JCExpression, JCExpression> result) {
        return make.If(
            instanceOf(value(), type),
            make.Return(result.apply(cast(type, value()))),
            null
        );
    }

    /** {@code if ($v instanceof Object[]) return <fn(((Object[]) $v))>;} */
    private JCStatement arrayTestReturn(java.util.function.Function<JCExpression, JCExpression> result) {
        JCExpression arrayType = make.TypeArray(types.qualIdent("java.lang.Object"));
        JCExpression arrayType2 = make.TypeArray(types.qualIdent("java.lang.Object"));
        return make.If(
            make.TypeTest(value(), arrayType),
            make.Return(result.apply(make.TypeCast(arrayType2, value()))),
            null
        );
    }

}
