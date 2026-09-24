package me.rapierxbox.shellyelevatev2.voice;

// entries of the wake word model picker
public abstract class WakeWordModel {
    public abstract String getDisplayName();

    public static class Installed extends WakeWordModel {
        private final String name;

        public Installed(String name) { this.name = name; }

        public String getName() { return name; }

        @Override public String getDisplayName() { return name; }
    }

    // a model that can be fetched from a github repo
    public abstract static class Remote extends WakeWordModel {
        // unique on disk name like okay_nabu_v2
        private final String name;
        // upstream filename stem like okay_nabu
        private final String stem;
        // folder inside the repo like okay_nabu/v2
        private final String folderPath;
        private final String tfliteUrl;
        // empty when the repo ships no companion json
        private final String jsonUrl;

        protected Remote(String name, String stem, String folderPath, String tfliteUrl, String jsonUrl) {
            this.name = name;
            this.stem = stem;
            this.folderPath = folderPath;
            this.tfliteUrl = tfliteUrl;
            this.jsonUrl = jsonUrl;
        }

        public String getName()       { return name; }
        public String getStem()       { return stem; }
        public String getFolderPath() { return folderPath; }
        public String getTfliteUrl()  { return tfliteUrl; }
        public String getJsonUrl()    { return jsonUrl; }

        @Override public String getDisplayName() {
            return folderPath.isEmpty() ? stem : stem + "  [" + folderPath + "]";
        }
    }

    // from the official esphome repo
    public static class Downloadable extends Remote {
        public Downloadable(String name, String stem, String folderPath, String tfliteUrl, String jsonUrl) {
            super(name, stem, folderPath, tfliteUrl, jsonUrl);
        }
    }

    // from the community repo
    public static class Experimental extends Remote {
        public Experimental(String name, String stem, String folderPath, String tfliteUrl, String jsonUrl) {
            super(name, stem, folderPath, tfliteUrl, jsonUrl);
        }
    }

    public static class Custom extends WakeWordModel {
        public static final Custom INSTANCE = new Custom();

        private Custom() {}

        @Override public String getDisplayName() { return "Custom..."; }
    }
}
