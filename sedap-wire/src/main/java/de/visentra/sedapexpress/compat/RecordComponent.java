package de.visentra.sedapexpress.compat;

import java.lang.reflect.Method;

/**
 * Stands in for {@code java.lang.reflect.RecordComponent}, because
 * {@code Class.getRecordComponents()} only exists from API 34. Dex files do not keep the declaration
 * order of record fields, so the order comes from {@code RecordLayouts}, which the build generates
 * from the upstream sources.
 */
public final class RecordComponent {

    private static final String MESSAGES_PACKAGE = "de.bundeswehr.uniity.sedapexpress.messages.";

    private final Class<?> owner;
    private final String name;

    private RecordComponent(Class<?> owner, String name) {
        this.owner = owner;
        this.name = name;
    }

    /** Returns the components in declaration order, or null if the class is not a known record. */
    public static RecordComponent[] of(Class<?> type) {
        final String binaryName = type.getName();
        if (!binaryName.startsWith(MESSAGES_PACKAGE)) {
            return null;
        }
        final String[] names = RecordLayouts.componentNames(binaryName.substring(MESSAGES_PACKAGE.length()));
        if (names == null) {
            return null;
        }
        final RecordComponent[] components = new RecordComponent[names.length];
        for (int i = 0; i < names.length; i++) {
            components[i] = new RecordComponent(type, names[i]);
        }
        return components;
    }

    public String getName() {
        return this.name;
    }

    public Method getAccessor() {
        try {
            return this.owner.getMethod(this.name);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("record accessor missing: " + this.name, e);
        }
    }
}
