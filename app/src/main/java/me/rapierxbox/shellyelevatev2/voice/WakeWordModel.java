package me.rapierxbox.shellyelevatev2.voice;

public abstract class WakeWordModel {
    public abstract String getDisplayName();

    public static class Installed extends WakeWordModel {
        private final String name;
        public Installed(String name) { this.name = name; }
        public String getName() { return name; }
        @Override public String getDisplayName() { return name; }
    }

    public static class Downloadable extends WakeWordModel {
        private final String name;       // unique save name, e.g. "okay_nabu_v2"
        private final String stem;       // original filename stem, e.g. "okay_nabu"
        private final String folderPath; // repo path to parent dir, e.g. "okay_nabu/v2"
        private final String tfliteUrl;
        private final String jsonUrl;

        public Downloadable(String name, String stem, String folderPath, String tfliteUrl, String jsonUrl) {
            this.name = name; this.stem = stem; this.folderPath = folderPath;
            this.tfliteUrl = tfliteUrl; this.jsonUrl = jsonUrl;
        }
        public String getName()        { return name; }
        public String getStem()        { return stem; }
        public String getFolderPath()  { return folderPath; }
        public String getTfliteUrl()   { return tfliteUrl; }
        public String getJsonUrl()     { return jsonUrl; }

        @Override public String getDisplayName() {
            return folderPath.isEmpty() ? stem : stem + "  [" + folderPath + "]";
        }
    }

    public static class Experimental extends WakeWordModel {
        private final String name;       // unique save name
        private final String stem;       // original filename stem
        private final String folderPath; // repo path to parent dir
        private final String tfliteUrl;
        private final String jsonUrl;

        public Experimental(String name, String stem, String folderPath, String tfliteUrl, String jsonUrl) {
            this.name = name; this.stem = stem; this.folderPath = folderPath;
            this.tfliteUrl = tfliteUrl; this.jsonUrl = jsonUrl;
        }
        public String getName()        { return name; }
        public String getStem()        { return stem; }
        public String getFolderPath()  { return folderPath; }
        public String getTfliteUrl()   { return tfliteUrl; }
        public String getJsonUrl()     { return jsonUrl; }

        @Override public String getDisplayName() {
            return folderPath.isEmpty() ? stem : stem + "  [" + folderPath + "]";
        }
    }

    public static class Custom extends WakeWordModel {
        public static final Custom INSTANCE = new Custom();
        private Custom() {}
        @Override public String getDisplayName() { return "Custom..."; }
    }
}
