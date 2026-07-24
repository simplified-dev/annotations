package dev.simplified.tostring.mutate;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.Tag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Names;
import dev.simplified.accessor.apt.AccessorReads;
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
import dev.simplified.tostring.apt.ToStringConfig;

import javax.annotation.processing.Messager;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

/**
 * Injects {@code toString} into a class or record carrying {@code @ToString}.
 *
 * <p>A delta on the equality pass rather than a second implementation of it:
 * the members come from the same selector, the array handling comes from the
 * same term emitter, and the superclass question is answered by the same
 * resolver. What is left here is the rendered shape.
 *
 * <p>A target that already declares {@code toString} is left alone with a note.
 * The equality pair is an error in the same position, and the difference is
 * that a collection's behaviour depends on the pair while nothing depends on
 * this member - a diagnostic the author already wrote is a diagnostic they
 * meant.
 */
public final class ToStringMutator {

    private static final String PASS = "tostring";
    private static final String STRING_FQN = "java.lang.String";

    private final JavacBridge bridge;
    private final Types typeUtils;
    private final Messager messager;
    private final AnnotationLookup lookup;
    private final TreeMaker make;
    private final Names names;
    private final JavacTypeFactory types;
    private final MemberTerms terms;

    public ToStringMutator(JavacBridge bridge, Types typeUtils, AnnotationLookup lookup,
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

    /**
     * Injects the member.
     *
     * @param targetElement the annotated type
     * @param config the resolved attributes
     * @param members the selected members, in emission order
     * @return false when the target has no resolvable source tree
     */
    public boolean mutate(TypeElement targetElement, ToStringConfig config,
                          java.util.List<MemberSpec> members) {
        JCClassDecl target = bridge.treeOf(targetElement);
        if (target == null) return false;
        if (AstMarkers.isPassMarked(target, PASS)) return true;
        AstMarkers.markPass(target, PASS);
        make.at(target.pos);

        if (declaresToString(target)) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                ToStringConfig.LABEL + " on " + targetElement.getSimpleName()
                    + ", which already declares toString - the written member is kept",
                targetElement);
            return true;
        }
        TypeElement finalOwner =
            SuperResolver.finalSupertypeMember(targetElement, SuperResolver.Member.TO_STRING);
        if (finalOwner != null) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                ToStringConfig.LABEL + " cannot generate toString on "
                    + targetElement.getSimpleName() + " - " + finalOwner.getQualifiedName()
                    + " declares it final",
                targetElement);
            return true;
        }

        boolean callSuper = SuperResolver.resolve(targetElement, config.callSuper(),
            ToStringConfig.FQN, ToStringConfig.LABEL,
            new SuperResolver.Member[]{SuperResolver.Member.TO_STRING}, lookup, messager);

        ContractAnnotations contracts =
            new ContractAnnotations(make, names, types, config.emitContracts());
        GeneratedAnnotations generated =
            new GeneratedAnnotations(make, types, config.emitGenerated());

        JCExpression rendered = render(targetElement, target, config, callSuper, members);
        JCMethodDecl method = make.MethodDef(
            make.Modifiers(Flags.PUBLIC, contracts.pureReturnNonNull()),
            names.fromString("toString"),
            types.qualIdent(STRING_FQN),
            List.nil(),
            List.nil(),
            List.nil(),
            make.Block(0, List.of(make.Return(rendered))),
            null
        );
        AstMarkers.markGenerated(method, generated);
        bridge.compat().appendDef(target, method);
        return true;
    }

    /**
     * The concatenation the member returns.
     *
     * <p>Built left-associatively off a leading string literal, which is what
     * makes the whole chain a string concatenation rather than an arithmetic
     * one - the first operand decides.
     */
    private JCExpression render(TypeElement targetElement, JCClassDecl target,
                                ToStringConfig config, boolean callSuper,
                                java.util.List<MemberSpec> members) {
        JCExpression out = make.Literal(targetElement.getSimpleName() + config.open());
        boolean first = true;

        if (callSuper) {
            out = make.Binary(Tag.PLUS, out, make.Literal("super="));
            out = make.Binary(Tag.PLUS, out, make.Apply(List.nil(),
                make.Select(make.Ident(names.fromString("super")), names.fromString("toString")),
                List.nil()));
            first = false;
        }
        for (MemberSpec member : members) {
            AccessorReads.Read read = AccessorReads.resolve(targetElement, target, member,
                config.useAccessors(), typeUtils, ToStringConfig.LABEL, messager);
            StringBuilder prefix = new StringBuilder();
            if (!first) prefix.append(", ");
            if (config.includeFieldNames()) prefix.append(member.label()).append('=');
            first = false;
            if (prefix.length() > 0) out = make.Binary(Tag.PLUS, out, make.Literal(prefix.toString()));
            out = make.Binary(Tag.PLUS, out, terms.print(MemberShape.of(read.type()),
                terms.read(make.Ident(names._this), read.name(), read.method())));
        }
        return make.Binary(Tag.PLUS, out, make.Literal(config.close()));
    }

    private static boolean declaresToString(JCClassDecl target) {
        for (JCTree def : target.defs) {
            if (def instanceof JCMethodDecl method
                && !AstMarkers.isGenerated(method)
                && method.name.contentEquals("toString")
                && method.params.isEmpty()) {
                return true;
            }
        }
        return false;
    }

}
