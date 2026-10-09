package me.rapierxbox.shellyelevatev2.voice;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import okhttp3.OkHttpClient;

public class WakeWordModelDownloaderTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // path to body and every other path is a 404
    private final Map<String, String> files = new HashMap<>();
    private ServerSocket server;
    private Thread serverThread;
    private final OkHttpClient client = new OkHttpClient();

    @Before
    public void start() throws IOException {
        files.put("/new.tflite", "new model");
        files.put("/new.json", "{\"new\":true}");
        server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        serverThread = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    answer(socket);
                } catch (IOException ignored) {
                    // closed by stop
                }
            }
        });
        serverThread.start();
    }

    @After
    public void stop() throws Exception {
        server.close();
        serverThread.join(2000);
    }

    // one plain http 1.0 answer per connection
    private void answer(Socket socket) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String path = in.readLine().split(" ")[1];
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            // skip the headers
        }
        String body = files.get(path);
        byte[] bytes = (body != null ? body : "gone").getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.0 " + (body != null ? "200 OK" : "404 Not Found") + "\r\n"
                + "Content-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getLocalPort() + path;
    }

    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void failedJsonKeepsTheInstalledModel() throws IOException {
        File dir = tmp.newFolder("wakewords");
        write(new File(dir, "m.tflite"), "old model");
        write(new File(dir, "m.json"), "{\"old\":true}");
        try {
            WakeWordModelDownloader.download(client, dir, "m", url("/new.tflite"), url("/missing.json"), pct -> {});
            fail("a failed json must fail the install");
        } catch (IOException expected) {
            // the old pair stays and no part file is left behind
        }
        assertEquals("old model", read(new File(dir, "m.tflite")));
        assertEquals("{\"old\":true}", read(new File(dir, "m.json")));
        assertArrayEquals(new String[]{"m.json", "m.tflite"}, sorted(dir.list()));
    }

    @Test
    public void successReplacesBothFiles() throws IOException {
        File dir = tmp.newFolder("wakewords");
        write(new File(dir, "m.tflite"), "old model");
        write(new File(dir, "m.json"), "{\"old\":true}");
        WakeWordModelDownloader.download(client, dir, "m", url("/new.tflite"), url("/new.json"), pct -> {});
        assertEquals("new model", read(new File(dir, "m.tflite")));
        assertEquals("{\"new\":true}", read(new File(dir, "m.json")));
        assertFalse(new File(dir, "m.tflite.part").exists());
        assertFalse(new File(dir, "m.json.part").exists());
    }

    @Test
    public void cancelledDownloadKeepsTheInstalledModel() throws IOException {
        File dir = tmp.newFolder("wakewords");
        write(new File(dir, "m.tflite"), "old model");
        write(new File(dir, "m.json"), "{\"old\":true}");
        try {
            WakeWordModelDownloader.download(client, dir, "m", url("/new.tflite"), url("/new.json"), pct -> {}, () -> true);
            fail("a cancelled download must not install");
        } catch (InterruptedIOException expected) {
            // cancelled before the first chunk was written
        }
        assertEquals("old model", read(new File(dir, "m.tflite")));
        assertArrayEquals(new String[]{"m.json", "m.tflite"}, sorted(dir.list()));
    }

    @Test
    public void wrongHashKeepsTheInstalledModel() throws IOException {
        File dir = tmp.newFolder("wakewords");
        write(new File(dir, "m.tflite"), "old model");
        try {
            WakeWordModelDownloader.download(client, dir, "m", url("/new.tflite"), "00", url("/new.json"), null,
                    pct -> {}, null);
            fail("a hash mismatch must fail the install");
        } catch (IOException expected) {
            // the old model stays
        }
        assertEquals("old model", read(new File(dir, "m.tflite")));
        assertArrayEquals(new String[]{"m.tflite"}, sorted(dir.list()));
    }

    @Test
    public void matchingHashInstalls() throws IOException {
        File dir = tmp.newFolder("wakewords");
        String sha = okio.ByteString.encodeUtf8("new model").sha256().hex();
        WakeWordModelDownloader.download(client, dir, "m", url("/new.tflite"), sha, null, null, pct -> {}, null);
        assertEquals("new model", read(new File(dir, "m.tflite")));
    }

    @Test
    public void partFilesAreUniquePerDownload() throws IOException {
        File dir = tmp.newFolder("wakewords");
        assertNotEquals(WakeWordModelDownloader.partFile(dir, "m.tflite"), WakeWordModelDownloader.partFile(dir, "m.tflite"));
    }

    private static String[] sorted(String[] names) {
        java.util.Arrays.sort(names);
        return names;
    }
}
