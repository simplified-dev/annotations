package dev.simplified.classbuilder.apt;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import java.util.Map;

/**
 * Read-only helper for pulling attribute values off {@link AnnotationMirror}s.
 */
public final class AnnotationLookup {

    public boolean hasAnnotation(Element element, String annotationFqn) {
        return findMirror(element, annotationFqn) != null;
    }

    public AnnotationMirror findMirror(Element element, String annotationFqn) {
        for (AnnotationMirror m : element.getAnnotationMirrors()) {
            if (m.getAnnotationType().toString().equals(annotationFqn)) return m;
        }
        return null;
    }

    public String stringAttr(Element element, String annotationFqn, String attr, String fallback) {
        return stringAttr(findMirror(element, annotationFqn), attr, fallback);
    }

    public boolean booleanAttr(Element element, String annotationFqn, String attr, boolean fallback) {
        return booleanAttr(findMirror(element, annotationFqn), attr, fallback);
    }

    public int intAttr(Element element, String annotationFqn, String attr, int fallback) {
        return intAttr(findMirror(element, annotationFqn), attr, fallback);
    }

    public String[] stringArrayAttr(Element element, String annotationFqn, String attr) {
        return stringArrayAttr(findMirror(element, annotationFqn), attr);
    }

    // ------------------------------------------------------------------
    // Mirror-keyed overloads - needed for nested annotation attributes
    // (e.g. @BuildRule.flag() / @BuildRule.obtainVia()), where the outer
    // element is a field but the values we need live inside another
    // AnnotationMirror returned by attrValue(outerMirror, "flag").
    // ------------------------------------------------------------------

    public String stringAttr(AnnotationMirror mirror, String attr, String fallback) {
        Object raw = attrValue(mirror, attr);
        return raw == null ? fallback : String.valueOf(raw);
    }

    public boolean booleanAttr(AnnotationMirror mirror, String attr, boolean fallback) {
        Object raw = attrValue(mirror, attr);
        if (raw instanceof Boolean b) return b;
        return fallback;
    }

    public int intAttr(AnnotationMirror mirror, String attr, int fallback) {
        Object raw = attrValue(mirror, attr);
        if (raw instanceof Integer i) return i;
        return fallback;
    }

    public String[] stringArrayAttr(AnnotationMirror mirror, String attr) {
        Object raw = attrValue(mirror, attr);
        if (raw == null) return new String[0];
        if (!(raw instanceof java.util.List<?> list)) return new String[0];
        String[] out = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Object v = list.get(i);
            if (v instanceof AnnotationValue av) out[i] = String.valueOf(av.getValue());
            else out[i] = String.valueOf(v);
        }
        return out;
    }

    /**
     * Reads a nested-annotation attribute value. Returns the inner
     * {@link AnnotationMirror} for walking, or {@code null} when the
     * attribute isn't set (in which case the caller treats it as the
     * annotation's default, which is the no-op form for our uses).
     */
    public AnnotationMirror nestedAnnotationValue(AnnotationMirror mirror, String attr) {
        Object raw = attrValue(mirror, attr);
        if (raw instanceof AnnotationMirror nested) return nested;
        return null;
    }

    private Object attrValue(Element element, String annotationFqn, String attr) {
        return attrValue(findMirror(element, annotationFqn), attr);
    }

    private Object attrValue(AnnotationMirror mirror, String attr) {
        if (mirror == null) return null;
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e : mirror.getElementValues().entrySet()) {
            if (e.getKey().getSimpleName().contentEquals(attr)) {
                return e.getValue().getValue();
            }
        }
        return null;
    }

}
