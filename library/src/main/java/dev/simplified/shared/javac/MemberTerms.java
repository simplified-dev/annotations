package dev.simplified.shared.javac;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.Tag;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;
import dev.simplified.shared.apt.MemberShape;

import java.util.function.Supplier;

/**
 * Per-member expressions for a generated {@code equals}, {@code hashCode} and
 * {@code toString}.
 *
 * <p>One emitter for all three because the interesting cases are the same
 * cases. An array has to be read by content in every one of them, a float has
 * to induce one partition in {@code equals} and the matching one in
 * {@code hashCode}, and getting either right in one member and wrong in another
 * is worse than getting both wrong - the inconsistency has no symptom until a
 * hash table starts losing entries.
 *
 * <p>Every read arrives as a {@link Supplier} rather than a node, because a
 * javac tree cannot be shared between two parents and several of these terms
 * need the same read two or three times.
 */
public final class MemberTerms {

    private static final String FQN_ARRAYS = "java.util.Arrays";
    private static final String FQN_OBJECTS = "java.util.Objects";
    private static final String FQN_FLOAT = "java.lang.Float";
    private static final String FQN_DOUBLE = "java.lang.Double";

    /** The odd prime the reference terms fold a null into, matching the accumulator's own scale. */
    private static final int NULL_HASH = 43;

    /** The two constants a boolean contributes, chosen to be far apart in the accumulator. */
    private static final int TRUE_HASH = 79;
    private static final int FALSE_HASH = 97;

    private final TreeMaker make;
    private final Names names;
    private final JavacTypeFactory types;

    public MemberTerms(TreeMaker make, Names names, JavacTypeFactory types) {
        this.make = make;
        this.names = names;
        this.types = types;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * A read of one member off a receiver.
     *
     * @param receiver the receiver expression, freshly minted
     * @param name the field or accessor name
     * @param method whether the read is a zero-arg call
     * @return the read expression
     */
    public JCExpression read(JCExpression receiver, String name, boolean method) {
        JCExpression select = make.Select(receiver, this.names.fromString(name));
        return method ? make.Apply(List.nil(), select, List.nil()) : select;
    }

    // ------------------------------------------------------------------
    // equals
    // ------------------------------------------------------------------

    /**
     * A boolean expression that is true when the two reads are <b>not</b> equal,
     * which is the shape an early-return chain wants.
     *
     * @param shape the member's emission row
     * @param mine a read off {@code this}
     * @param theirs a read off the other instance
     * @return the inequality test
     */
    public JCExpression notEqual(MemberShape shape, JCExpression mine, JCExpression theirs) {
        switch (shape) {
            case BOOLEAN:
            case INTEGRAL:
            case LONG:
                return make.Binary(Tag.NE, mine, theirs);
            case FLOAT:
                return make.Binary(Tag.NE, compare(FQN_FLOAT, mine, theirs), make.Literal(0));
            case DOUBLE:
                return make.Binary(Tag.NE, compare(FQN_DOUBLE, mine, theirs), make.Literal(0));
            case PRIMITIVE_ARRAY:
                return not(call(FQN_ARRAYS, "equals", mine, theirs));
            case DEEP_ARRAY:
                return not(call(FQN_ARRAYS, "deepEquals", mine, theirs));
            default:
                return not(call(FQN_OBJECTS, "equals", mine, theirs));
        }
    }

    // ------------------------------------------------------------------
    // hashCode
    // ------------------------------------------------------------------

    /**
     * The {@code int} term one member contributes to the hash accumulator.
     *
     * <p>A {@code double} needs its bit pattern once rather than twice, so it
     * is the one row that emits a statement; {@code prelude} is where that
     * local lands.
     *
     * @param shape the member's emission row
     * @param read mints a fresh read of the member
     * @param prelude sink for any statement the term needs computed first
     * @param localName name for that local, unique within the method
     * @return the term
     */
    public JCExpression hash(MemberShape shape, Supplier<JCExpression> read,
                             ListBuffer<JCStatement> prelude, String localName) {
        switch (shape) {
            case BOOLEAN:
                return make.Parens(make.Conditional(read.get(),
                    make.Literal(TRUE_HASH), make.Literal(FALSE_HASH)));
            case INTEGRAL:
                return read.get();
            case LONG:
                return foldLong(read);
            case FLOAT:
                return call(FQN_FLOAT, "floatToIntBits", read.get());
            case DOUBLE:
                prelude.append(make.VarDef(
                    make.Modifiers(Flags.FINAL),
                    names.fromString(localName),
                    make.TypeIdent(TypeTag.LONG),
                    call(FQN_DOUBLE, "doubleToLongBits", read.get())
                ));
                return foldLong(() -> make.Ident(names.fromString(localName)));
            case PRIMITIVE_ARRAY:
                return call(FQN_ARRAYS, "hashCode", read.get());
            case DEEP_ARRAY:
                return call(FQN_ARRAYS, "deepHashCode", read.get());
            default:
                return make.Parens(make.Conditional(
                    make.Binary(Tag.EQ, read.get(), nullLit()),
                    make.Literal(NULL_HASH),
                    make.Apply(List.nil(),
                        make.Select(read.get(), names.fromString("hashCode")), List.nil())
                ));
        }
    }

    // ------------------------------------------------------------------
    // toString
    // ------------------------------------------------------------------

    /**
     * The expression one member contributes to a string concatenation.
     *
     * <p>Only the two array rows need anything: everything else concatenates to
     * something readable already, including a null reference.
     *
     * @param shape the member's emission row
     * @param read a read of the member
     * @return the printable expression
     */
    public JCExpression print(MemberShape shape, JCExpression read) {
        switch (shape) {
            case PRIMITIVE_ARRAY: return call(FQN_ARRAYS, "toString", read);
            case DEEP_ARRAY: return call(FQN_ARRAYS, "deepToString", read);
            default: return read;
        }
    }

    // ------------------------------------------------------------------
    // Primitives of the emitter itself
    // ------------------------------------------------------------------

    /** {@code (int) (v ^ (v >>> 32))} - the standard fold of a long onto an int. */
    private JCExpression foldLong(Supplier<JCExpression> read) {
        return make.TypeCast(make.TypeIdent(TypeTag.INT),
            make.Parens(make.Binary(Tag.BITXOR,
                read.get(),
                make.Parens(make.Binary(Tag.USR, read.get(), make.Literal(32))))));
    }

    private JCExpression compare(String boxFqn, JCExpression a, JCExpression b) {
        return call(boxFqn, "compare", a, b);
    }

    private JCExpression call(String ownerFqn, String method, JCExpression... args) {
        return make.Apply(List.nil(),
            make.Select(types.qualIdent(ownerFqn), names.fromString(method)),
            List.from(args));
    }

    private JCExpression not(JCExpression expression) {
        return make.Unary(Tag.NOT, expression);
    }

    private JCExpression nullLit() {
        return make.Literal(TypeTag.BOT, null);
    }

}
