package dev.simplified.classbuilder.apt;

import javax.lang.model.SourceVersion;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The simple names one generated file has spent, and the import block that
 * follows from them.
 *
 * <p>Both source emitters shorten a fully qualified name by importing it, and
 * the bookkeeping lives here rather than in either of them because a simple
 * name is a property of the <b>file</b>, not of the reference being written. A
 * set keyed on the fully qualified name cannot see that {@code acme.Arrays} and
 * {@code zoo.Arrays} both want to be spelled {@code Arrays} - it happily
 * imports both, and javac then rejects the generated file on a line the author
 * cannot edit.
 *
 * <p>The first claimant of a simple name keeps it; every later type wanting the
 * same one is spelled out inline and claims no import. Claim order is emission
 * order, so it is deterministic for a given target.
 *
 * <p><b>A direct member of {@code java.lang} and a type in the file's own
 * package claim their name too</b>, even though neither takes an import entry.
 * Skipping the claim is what lets a field typed {@code acme.String} import
 * itself - legal Java that shadows {@code java.lang.String} for the whole file,
 * after which every other {@code String} the emitter wrote silently means
 * {@code acme.String}.
 */
final class ImportRegistry {

    private static final String JAVA_LANG = "java.lang";

    private final String packageName;

    /** Simple name to the fully qualified name that took it. */
    private final Map<String, String> claimed = new HashMap<>();

    private final Set<String> imports = new TreeSet<>();

    ImportRegistry(String packageName) {
        this.packageName = packageName;
    }

    /**
     * Resolves how {@code fqn} must be spelled in this file, taking its simple
     * name and an import entry when both are still free.
     *
     * <p>A nested type arrives with its owner as its package -
     * {@code java.util.Map.Entry} - and is imported under that name, which is
     * legal and is what the emitters have always produced.
     *
     * <p><b>A fragment that is not a type name claims nothing and imports
     * nothing.</b> This is the single funnel every spelling passes through and
     * therefore the only place the emitters' tokenizers can be guarded: a
     * trailing dot - which is what a bare package qualifier looks like when a
     * type-use annotation splits a qualified name - yields an empty simple name,
     * and an import minted from one is not even parseable. Echoing the argument
     * keeps a tokenizer mistake inside the type reference that caused it.
     *
     * @param fqn the fully qualified name of the type being referenced
     * @return the simple name when this type holds the claim, the argument
     *         unchanged when some other type already does
     */
    String use(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        String simple = lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
        if (!SourceVersion.isIdentifier(simple)) return fqn;
        String holder = claimed.putIfAbsent(simple, fqn);
        if (holder != null && !holder.equals(fqn)) return fqn;
        // A type in the default package: nothing to shorten, and an import of it
        // is not expressible.
        if (lastDot < 0) return fqn;

        String pkg = fqn.substring(0, lastDot);
        if (!pkg.equals(packageName) && !pkg.equals(JAVA_LANG)) imports.add(fqn);
        return simple;
    }

    /**
     * Claims a name the generated file declares or writes bare, which takes no
     * import and must outrank anything else wanting the same spelling.
     *
     * <p>The type being generated and the type it is generated for are both
     * named unqualified throughout the emitted source and neither is written
     * through {@link #use}, so without this a field typed {@code acme.Board} on
     * an interface {@code demo.Board} imports itself and every bare
     * {@code Board} below means the wrong one.
     *
     * @param simpleName the name the file spends
     */
    void declare(String simpleName) {
        claimed.putIfAbsent(simpleName, packageName.isEmpty() ? simpleName : packageName + '.' + simpleName);
    }

    /**
     * Renders the import block, sorted, with the blank line that separates it
     * from the type declaration.
     *
     * @return the block, or {@code ""} when nothing was imported
     */
    String block() {
        if (imports.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(imports.size() * 32);
        for (String imp : imports) sb.append("import ").append(imp).append(";\n");
        return sb.append('\n').toString();
    }

}
