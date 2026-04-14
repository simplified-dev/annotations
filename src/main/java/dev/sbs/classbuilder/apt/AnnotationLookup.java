package dev.sbs.classbuilder.apt;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import java.util.Map;

/**
 * Read-only helper for pulling attribute values off {@link AnnotationMirror}s.
 */
final class AnnotationLookup {

    boolean hasAnnotation(Element element, String annotationFqn) {
        return findMirror(element, annotationFqn) != null;
    }

    AnnotationMirror findMirror(Element element, String annotationFqn) {
        for (AnnotationMirror m : element.getAnnotationMirrors()) {
            if (m.getAnnotationType().toString().equals(annotationFqn)) return m;
        }
        return null;
    }

    String stringAttr(Element element, String annotationFqn, String attr, String fallback) {
        Object raw = attrValue(element, annotationFqn, attr);
        return raw == null ? fallback : String.valueOf(raw);
    }

    boolean booleanAttr(Element element, String annotationFqn, String attr, boolean fallback) {
        Object raw = attrValue(element, annotationFqn, attr);
        if (raw instanceof Boolean b) return b;
        return fallback;
    }

    int intAttr(Element element, String annotationFqn, String attr, int fallback) {
        Object raw = attrValue(element, annotationFqn, attr);
        if (raw instanceof Integer i) return i;
        return fallback;
    }

    String[] stringArrayAttr(Element element, String annotationFqn, String attr) {
        Object raw = attrValue(element, annotationFqn, attr);
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

    private Object attrValue(Element element, String annotationFqn, String attr) {
        AnnotationMirror mirror = findMirror(element, annotationFqn);
        if (mirror == null) return null;
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e : mirror.getElementValues().entrySet()) {
            if (e.getKey().getSimpleName().contentEquals(attr)) {
                return e.getValue().getValue();
            }
        }
        return null;
    }

}
