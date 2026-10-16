package me.rapierxbox.shellyelevatev2.voice.speaker;

import java.io.File;
import java.io.IOException;

import me.rapierxbox.shellyelevatev2.helper.HttpDownloader;

// the redimnet2 speaker models converted by tools/speaker-models and pinned by hash
public final class SpeakerModels {
    public static final String DEFAULT = "b2";
    private static final String BASE =
            "https://github.com/RapierXbox/ShellyElevate/releases/download/speaker-models-v1/";

    private SpeakerModels() {}

    static String sha256(String id) {
        switch (id) {
            case "b0": return "0eb800e9f7f67538b6ab6fa96b64f119ab57b87df2a2bcea82c19f5dce930837";
            case "b2": return "105b6b92b4857294b94db4d61ca514ef246640ea860be6368cdf05bb48c3011e";
            case "b3": return "6ec3835f2e40d3999ededc4a78dea48428c410761fc7f2d3559c7250a1518779";
            default: return null;
        }
    }

    static String id(String pref) {
        return sha256(pref) != null ? pref : DEFAULT;
    }

    static File file(File dir, String id) {
        return new File(dir, "redimnet2_" + id + ".tflite");
    }

    // downloads the model once. blocks so call it off the main thread
    static void ensure(File dir, String id) throws IOException {
        File file = file(dir, id);
        if (file.isFile()) return;
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("could not create " + dir);
        File part = new File(dir, file.getName() + ".part");
        try {
            HttpDownloader.download(HttpDownloader.defaultClient(), BASE + file.getName(), part, pct -> {});
            HttpDownloader.requireSha256(part, sha256(id));
            if (!part.renameTo(file)) throw new IOException("could not rename " + part);
        } finally {
            part.delete();
        }
    }
}
