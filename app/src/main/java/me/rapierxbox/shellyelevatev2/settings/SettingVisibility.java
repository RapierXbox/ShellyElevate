package me.rapierxbox.shellyelevatev2.settings;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// whether a setting can take effect right now and so belongs in a ui
// the rule is the one protocol-v1 documents for visible_if and requires
public final class SettingVisibility {
    private SettingVisibility() {}

    // current value of a setting. null means use the default of the def
    public interface Values {
        Object get(String key);
    }

    public interface Defs {
        SettingDef get(String key);
    }

    private enum State {
        // requires and visible_if hold
        VISIBLE,
        // a requires failed so the hardware or feature is missing
        UNAVAILABLE,
        // visible_if failed or references a setting that is itself not shown
        INACTIVE
    }

    public static boolean visible(SettingDef def, Values values, Map<String, ?> caps) {
        return visible(def, values, caps, SettingsRegistry::get);
    }

    public static boolean visible(SettingDef def, Values values, Map<String, ?> caps, Defs defs) {
        if (def.hidden) return false;
        return state(def, values, caps, defs, new HashSet<>()) == State.VISIBLE;
    }

    // true when every requires entry holds
    public static boolean available(SettingDef def, Map<String, ?> caps) {
        for (SettingDef.Requirement requirement : def.requires) {
            if (!met(requirement, caps)) return false;
        }
        return true;
    }

    private static State state(SettingDef def, Values values, Map<String, ?> caps, Defs defs, Set<String> visiting) {
        if (!available(def, caps)) return State.UNAVAILABLE;
        if (def.visibleIf.isEmpty()) return State.VISIBLE;
        // a cycle never resolves so every setting on it stays hidden
        if (!visiting.add(def.key)) return State.INACTIVE;
        try {
            for (SettingDef.Condition condition : def.visibleIf) {
                SettingDef parent = defs.get(condition.key);
                if (parent == null) return State.INACTIVE;
                State parentState = state(parent, values, caps, defs, visiting);
                if (parentState == State.INACTIVE) return State.INACTIVE;
                // a parent the device lacks counts as off so a setting that replaces it still shows
                Object value = parentState == State.UNAVAILABLE ? fallback(parent) : value(parent, values);
                if (!holds(condition, value)) return State.INACTIVE;
            }
            return State.VISIBLE;
        } finally {
            visiting.remove(def.key);
        }
    }

    // false for a bool and the default for anything else
    static Object fallback(SettingDef def) {
        return SettingDef.TYPE_BOOL.equals(def.type) ? Boolean.FALSE : def.defaultValue;
    }

    private static Object value(SettingDef def, Values values) {
        Object value = values.get(def.key);
        return value == null ? def.defaultValue : value;
    }

    static boolean holds(SettingDef.Condition condition, Object value) {
        switch (condition.op) {
            case SettingDef.Condition.EQ:
                return same(value, condition.values.get(0));
            case SettingDef.Condition.NE:
                return !same(value, condition.values.get(0));
            case SettingDef.Condition.IN:
                for (Object allowed : condition.values) {
                    if (same(value, allowed)) return true;
                }
                return false;
            default:
                return false;
        }
    }

    // numbers compare by value so 1 and 1.0 and a long 1 match
    private static boolean same(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue()) == 0;
        }
        return a == null ? b == null : a.equals(b);
    }

    static boolean met(SettingDef.Requirement requirement, Map<String, ?> caps) {
        Object value = caps == null ? null : caps.get(requirement.cap);
        if (requirement.min != null) {
            return value instanceof Number && ((Number) value).doubleValue() >= requirement.min;
        }
        if (value == null) return false;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).doubleValue() != 0;
        if (value instanceof String) return !((String) value).isEmpty();
        return true;
    }
}
