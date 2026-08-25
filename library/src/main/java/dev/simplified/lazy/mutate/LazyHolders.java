package dev.simplified.lazy.mutate;

import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Names;
import dev.simplified.shared.javac.JavacTypeFactory;

/**
 * The storage shape a {@code @Lazy} field is rewritten to, and the single place
 * it is spelled.
 *
 * <p>A rewritten field holds an {@code AtomicReference<Supplier<T>>}: the
 * reference is the deferred computation until it is read, the state token that
 * says whether the value exists, and the monitor the synthesised getter locks
 * on. The memoized result lands in a sibling field named by
 * {@link #valueField}.
 *
 * <p>Three passes construct this type - the {@code @Lazy} rewrite itself and
 * both constructor factories - and they have to agree exactly, down to whether
 * a primitive field's type argument is boxed. Building it here rather than in
 * each of them is what keeps them from disagreeing.
 */
public final class LazyHolders {

    /** Fully-qualified name of the holder the deferred supplier lives in. */
    public static final String ATOMIC_REFERENCE_FQN = "java.util.concurrent.atomic.AtomicReference";

    /** Fully-qualified name of the deferred computation's type. */
    public static final String SUPPLIER_FQN = "java.util.function.Supplier";

    private static final String OBJECTS_FQN = "java.util.Objects";

    private LazyHolders() {}

    /**
     * Returns the diagnostic a missing supplier fails with, naming the setter
     * that would have filled it.
     *
     * @param owner fully-qualified name of the class declaring the field
     * @param field the field's name
     * @return the failure message
     */
    public static String missingSupplierMessage(String owner, String field) {
        return "@Lazy field '" + field + "' on " + owner
            + " has no supplier - call the builder's " + field
            + "(..) setter or give the field an initializer";
    }

    /**
     * Returns the name of the field holding a {@code @Lazy} field's memoized
     * value.
     *
     * <p>Spelled with the {@code $prefix$} shape the rest of the pipeline uses,
     * so the plugin's synthetic-member filters - which test for a leading
     * {@code $} - keep recognising it.
     *
     * @param name the annotated field's name
     * @return the value field's name
     */
    public static String valueField(String name) {
        return "$value$" + name;
    }

    /**
     * Returns the name of the method that resolves a {@code @Lazy} field,
     * computing the value on first call and reusing it after.
     *
     * <p>The memoizing logic lives here rather than in the public getter so
     * there is still one correct way to read the field when the author writes
     * that getter themselves. Reading the storage directly cannot work - it
     * holds the supplier, not the value - and calling the supplier by hand
     * would recompute on every read.
     *
     * @param name the annotated field's name
     * @return the resolver's name
     */
    public static String resolver(String name) {
        return "$resolve$" + name;
    }

    /**
     * Builds the {@code Supplier<T>} a holder wraps, boxing a primitive type
     * argument.
     *
     * <p>A {@code Supplier<int>} does not exist, so an {@code int} field defers
     * through a {@code Supplier<Integer>} and unboxes once when the value is
     * computed. The value field itself stays primitive, which is what keeps the
     * read path free of boxing.
     *
     * @param make the tree maker
     * @param types the type factory
     * @param typeDisplay the field's declared type, as rendered by {@code javax.lang.model}
     * @return the supplier type expression
     */
    public static JCExpression supplierType(TreeMaker make, JavacTypeFactory types, String typeDisplay) {
        return make.TypeApply(types.qualIdent(SUPPLIER_FQN),
            List.of(types.parseBoxedType(typeDisplay)));
    }

    /**
     * Builds the {@code AtomicReference<Supplier<T>>} a rewritten field is
     * declared as.
     *
     * @param make the tree maker
     * @param types the type factory
     * @param typeDisplay the field's declared type, as rendered by {@code javax.lang.model}
     * @return the holder type expression
     */
    public static JCExpression holderType(TreeMaker make, JavacTypeFactory types, String typeDisplay) {
        return make.TypeApply(types.qualIdent(ATOMIC_REFERENCE_FQN),
            List.of(supplierType(make, types, typeDisplay)));
    }

    /**
     * Builds {@code new AtomicReference<Supplier<T>>(<supplier>)}.
     *
     * <p>The type arguments are written out rather than left to a diamond,
     * because the expression is synthesised into positions where javac has no
     * target type to infer them from.
     *
     * @param make the tree maker
     * @param types the type factory
     * @param typeDisplay the field's declared type, as rendered by {@code javax.lang.model}
     * @param supplier the deferred computation to wrap
     * @return the holder instantiation
     */
    public static JCExpression newHolder(TreeMaker make, JavacTypeFactory types,
                                         String typeDisplay, JCExpression supplier) {
        return make.NewClass(null, List.nil(),
            holderType(make, types, typeDisplay), List.of(supplier), null);
    }

    /**
     * Builds {@code new AtomicReference<Supplier<T>>(Objects.requireNonNull(<supplier>, ...))}
     * for a supplier arriving from a builder slot.
     *
     * <p>Null-checked because a slot that was never filled is the one way a
     * missing supplier reaches a holder, and the failure should name the field
     * at {@code build()} rather than surface as a bare NPE at the first read.
     *
     * @param make the tree maker
     * @param names the name table
     * @param types the type factory
     * @param typeDisplay the field's declared type, as rendered by {@code javax.lang.model}
     * @param supplier the deferred computation to wrap
     * @param owner fully-qualified name of the class declaring the field
     * @param field the field's name
     * @return the holder instantiation, wrapping a null-checked supplier
     */
    public static JCExpression newCheckedHolder(TreeMaker make, Names names, JavacTypeFactory types,
                                                String typeDisplay, JCExpression supplier,
                                                String owner, String field) {
        JCExpression checked = make.Apply(
            List.nil(),
            make.Select(types.qualIdent(OBJECTS_FQN), names.fromString("requireNonNull")),
            List.of(supplier, make.Literal(missingSupplierMessage(owner, field)))
        );
        return newHolder(make, types, typeDisplay, checked);
    }

}
