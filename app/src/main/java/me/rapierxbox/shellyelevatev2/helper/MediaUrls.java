package me.rapierxbox.shellyelevatev2.helper;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

// home assistant builds media and tts urls from its configured url which can name https while it serves http
// a port only speaks one of them and the dashboard proves which so its scheme wins on the same host and port
public final class MediaUrls {
    private MediaUrls() {}

    public static String forPlayback(String url) {
        return matchScheme(url, ServiceHelper.getWebviewUrl());
    }

    static String matchScheme(String url, String dashboardUrl) {
        if (url == null || dashboardUrl == null || dashboardUrl.isEmpty()) return url;
        try {
            URI media = new URI(url);
            URI dashboard = new URI(dashboardUrl.trim());
            String mediaScheme = scheme(media);
            String dashboardScheme = scheme(dashboard);
            if (mediaScheme == null || dashboardScheme == null || mediaScheme.equals(dashboardScheme)) return url;
            if (media.getHost() == null || !media.getHost().equalsIgnoreCase(dashboard.getHost())) return url;
            if (port(media, mediaScheme) != port(dashboard, dashboardScheme)) return url;
            return dashboardScheme + url.substring(url.indexOf(':'));
        } catch (URISyntaxException e) {
            return url;
        }
    }

    // http or https and null for anything else
    private static String scheme(URI uri) {
        String s = uri.getScheme();
        if (s == null) return null;
        s = s.toLowerCase(Locale.ROOT);
        return s.equals("http") || s.equals("https") ? s : null;
    }

    private static int port(URI uri, String scheme) {
        if (uri.getPort() != -1) return uri.getPort();
        return scheme.equals("https") ? 443 : 80;
    }
}
