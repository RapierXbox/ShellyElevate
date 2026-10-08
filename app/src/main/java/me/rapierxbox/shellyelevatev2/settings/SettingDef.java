package me.rapierxbox.shellyelevatev2.settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

// one persisted setting as described by protocol-v1 SettingDef
public final class SettingDef {
    public static final String TYPE_BOOL = "bool";
    public static final String TYPE_INT = "int";
    public static final String TYPE_FLOAT = "float";
    public static final String TYPE_STRING = "string";
    public static final String TYPE_ENUM = "enum";
    public static final String TYPE_STRING_LIST = "string_list";

    // an enum entry. value is an Integer or a String
    public static final class Option {
        public final Object value;
        public final String label;

        public Option(Object value, String label) {
            this.value = value;
            this.label = label;
        }
    }

    // one visible_if entry. the setting shows only while the value of key matches
    public static final class Condition {
        public static final String EQ = "eq";
        public static final String NE = "ne";
        public static final String IN = "in";

        public final String key;
        public final String op;
        // one value for eq and ne and the allowed values for in
        public final List<Object> values;

        private Condition(String key, String op, List<Object> values) {
            this.key = key;
            this.op = op;
            this.values = Collections.unmodifiableList(values);
        }

        public static Condition eq(String key, Object value) {
            return new Condition(key, EQ, Collections.singletonList(value));
        }

        public static Condition ne(String key, Object value) {
            return new Condition(key, NE, Collections.singletonList(value));
        }

        public static Condition in(String key, Object... values) {
            return new Condition(key, IN, Arrays.asList(values));
        }
    }

    // one requires entry. the capability must be truthy or at least min
    public static final class Requirement {
        public final String cap;
        public final Integer min;

        public Requirement(String cap, Integer min) {
            this.cap = cap;
            this.min = min;
        }
    }

    public final String key;
    public final String type;
    // Boolean Integer Double String or List of String depending on type
    public final Object defaultValue;
    public final String category;
    public final String label;
    public final String description;
    public final Double min;
    public final Double max;
    public final Double step;
    public final String unit;
    public final List<Option> options;
    public final boolean secret;
    public final boolean perDevice;
    public final boolean requiresRestart;
    public final boolean deprecated;
    public final String replacedBy;
    // all must hold for the setting to show. empty when it always applies
    public final List<Condition> visibleIf;
    public final List<Requirement> requires;
    // never shown in a ui but still readable and writable over the api
    public final boolean hidden;
    // shown but not editable in a ui
    public final boolean readOnly;

    private SettingDef(Builder b) {
        key = b.key;
        type = b.type;
        defaultValue = b.defaultValue;
        category = b.category;
        label = b.label;
        description = b.description;
        min = b.min;
        max = b.max;
        step = b.step;
        unit = b.unit;
        options = b.options == null ? null : Collections.unmodifiableList(b.options);
        secret = b.secret;
        perDevice = b.perDevice;
        requiresRestart = b.requiresRestart;
        deprecated = b.deprecated;
        replacedBy = b.replacedBy;
        visibleIf = Collections.unmodifiableList(new ArrayList<>(b.visibleIf));
        requires = Collections.unmodifiableList(new ArrayList<>(b.requires));
        hidden = b.hidden;
        readOnly = b.readOnly;
    }

    // true when enum values are ints and so stored with putInt
    public boolean hasIntOptions() {
        return options != null && !options.isEmpty() && options.get(0).value instanceof Integer;
    }

    public static final class Builder {
        private final String key;
        private final String type;
        private final Object defaultValue;
        private final String category;
        private final String label;
        private String description;
        private Double min;
        private Double max;
        private Double step;
        private String unit;
        private List<Option> options;
        private boolean secret;
        private boolean perDevice;
        private boolean requiresRestart;
        private boolean deprecated;
        private String replacedBy;
        private final List<Condition> visibleIf = new ArrayList<>();
        private final List<Requirement> requires = new ArrayList<>();
        private boolean hidden;
        private boolean readOnly;

        public Builder(String key, String type, Object defaultValue, String category, String label) {
            this.key = key;
            this.type = type;
            this.defaultValue = defaultValue;
            this.category = category;
            this.label = label;
        }

        public Builder description(String value) { description = value; return this; }
        public Builder range(double from, double to) { min = from; max = to; return this; }
        public Builder min(double value) { min = value; return this; }
        public Builder max(double value) { max = value; return this; }
        public Builder step(double value) { step = value; return this; }
        public Builder unit(String value) { unit = value; return this; }
        public Builder options(List<Option> value) { options = value; return this; }
        public Builder secret() { secret = true; return this; }
        public Builder perDevice() { perDevice = true; return this; }
        public Builder requiresRestart() { requiresRestart = true; return this; }
        public Builder deprecated(String replacement) { deprecated = true; replacedBy = replacement; return this; }
        public Builder visibleIf(Condition... conditions) { visibleIf.addAll(Arrays.asList(conditions)); return this; }
        public Builder requires(String cap) { requires.add(new Requirement(cap, null)); return this; }
        public Builder requires(String cap, int min) { requires.add(new Requirement(cap, min)); return this; }
        public Builder hidden() { hidden = true; return this; }
        public Builder readOnly() { readOnly = true; return this; }

        public SettingDef build() {
            return new SettingDef(this);
        }
    }
}
