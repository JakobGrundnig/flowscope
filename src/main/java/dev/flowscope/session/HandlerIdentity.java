package dev.flowscope.session;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.function.Supplier;

import net.neoforged.neoforge.transfer.ResourceHandler;

/**
 * Decides whether two item/fluid handlers are backed by the very same storage, without mod-specific code.
 *
 * Many multiblocks hand out the controller's handler object directly from every part (then plain identity works).
 * Others wrap it in a thin forwarding object per part (e.g. a "delegating" handler holding a field with the real
 * handler, or a supplier returning it). For those, one level of forwarding is followed: an instance field whose value
 * is a ResourceHandler, or a Supplier field whose value is a ResourceHandler. The result must still be the identical
 * object, so unrelated inventories never match. Used only during the rare, bounded multiblock-part search and when
 * the lens is used; reflection results are cached per class. Failures (inaccessible classes, throwing suppliers)
 * simply mean "no match".
 */
final class HandlerIdentity {
    private static final Field[] NONE = new Field[0];

    private static final ClassValue<Field[]> FORWARDING_FIELDS = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> type) {
            ArrayList<Field> fields = new ArrayList<>(2);
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    Class<?> ft = f.getType();
                    if (ResourceHandler.class.isAssignableFrom(ft) || Supplier.class.isAssignableFrom(ft)) {
                        try {
                            f.setAccessible(true);
                            fields.add(f);
                        } catch (RuntimeException e) {
                            // not accessible (module rules): ignore this field
                        }
                    }
                }
            }
            return fields.isEmpty() ? NONE : fields.toArray(new Field[0]);
        }
    };

    private HandlerIdentity() {
    }

    /**
     * True if {@code a} and {@code b} are the same handler, or one forwards directly to the other or to the same one.
     * Supplier fields (which run the other mod's code) are only followed for {@code a}, the handler of the recorded
     * or clicked block; for {@code b} only plain fields are read, so a search over many nearby blocks never calls
     * into their code beyond the capability lookup itself.
     */
    static boolean sameStorage(Object a, Object b) {
        return a != null && sameStorage(a, inner(a, true), b);
    }

    /** As {@link #sameStorage(Object, Object)}, with {@code ia = inner(a, true)} computed once by the caller. */
    static boolean sameStorage(Object a, Object ia, Object b) {
        if (a == null || b == null) {
            return false;
        }
        if (a == b) {
            return true;
        }
        if (ia == b) {
            return true;
        }
        Object ib = inner(b, false);
        return ib == a || (ia != null && ia == ib);
    }

    /** Like {@link #sameStorage} but following suppliers on both sides (only for two handlers, e.g. on a click). */
    static boolean sameStorageBoth(Object a, Object b) {
        if (sameStorage(a, b)) {
            return true;
        }
        Object ib = b != null ? inner(b, true) : null;
        return ib != null && (ib == a || ib == inner(a, true));
    }

    /** The handler that {@code handler} forwards to (one level), or null if none can be found. */
    static Object inner(Object handler, boolean followSuppliers) {
        Field[] fields;
        try {
            fields = FORWARDING_FIELDS.get(handler.getClass());
        } catch (RuntimeException e) {
            return null;
        }
        for (Field f : fields) {
            try {
                Object v = f.get(handler);
                if (v instanceof Supplier<?> s && !(v instanceof ResourceHandler<?>)) {
                    if (!followSuppliers) {
                        continue;
                    }
                    v = s.get();
                }
                if (v instanceof ResourceHandler<?> && v != handler) {
                    return v;
                }
            } catch (RuntimeException | IllegalAccessException | LinkageError e) {
                // untrusted code or inaccessible field: no match through this field
            }
        }
        return null;
    }
}
