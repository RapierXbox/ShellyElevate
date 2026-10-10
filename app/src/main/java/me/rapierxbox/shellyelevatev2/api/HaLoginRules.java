package me.rapierxbox.shellyelevatev2.api;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

// pure rules of the home assistant dashboard login. no android so unit tests cover them
public final class HaLoginRules {
    // a second login page this soon after handing over the session means ha refused the token
    public static final long REFUSED_WINDOW_MS = 30_000;

    private HaLoginRules() {}

    // scheme host and port the way location.origin shows them or null for anything but http and https
    public static String originOf(String url) {
        if (url == null || url.trim().isEmpty()) return null;
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.isEmpty()) return null;
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return null;
            int port = uri.getPort();
            boolean defaultPort = port == -1
                    || (scheme.equals("http") && port == 80)
                    || (scheme.equals("https") && port == 443);
            return scheme + "://" + host.toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    // the client id the ha frontend refreshes with. it must match the one the token was made for
    public static String clientIdFor(String origin) {
        return origin + "/";
    }

    public static boolean sameOrigin(String url, String origin) {
        return origin != null && origin.equals(originOf(url));
    }

    // the ha login page the frontend redirects to without a valid session
    public static boolean isAuthorizePage(String url, String origin) {
        if (!sameOrigin(url, origin)) return false;
        try {
            String path = new URI(url.trim()).getRawPath();
            return path != null && path.startsWith("/auth/authorize");
        } catch (URISyntaxException e) {
            return false;
        }
    }

    public static boolean refused(long lastHandOverMs, long nowMs) {
        return lastHandOverMs > 0 && nowMs - lastHandOverMs < REFUSED_WINDOW_MS;
    }

    // stores the session the way the ha frontend keeps it then opens the dashboard
    // the access token starts expired so the frontend refreshes it on its own
    // the page checks its own origin so the token never lands on another site
    public static String loginScript(String origin, String clientId, String refreshToken, String target) {
        return "(function(){try{"
                + "if(location.protocol+'//'+location.host!==" + jsString(origin) + ")return;"
                + "localStorage.setItem('hassTokens',JSON.stringify({"
                + "hassUrl:" + jsString(origin) + ","
                + "clientId:" + jsString(clientId) + ","
                + "refresh_token:" + jsString(refreshToken) + ","
                + "access_token:'',token_type:'Bearer',expires_in:0,expires:0}));"
                + "location.replace(" + jsString(target) + ");"
                + "}catch(e){}})();";
    }

    // drops the stored session so the page falls back to the login
    public static String logoutScript(String origin) {
        return "(function(){try{"
                + "if(location.protocol+'//'+location.host!==" + jsString(origin) + ")return;"
                + "localStorage.removeItem('hassTokens');"
                + "location.reload();"
                + "}catch(e){}})();";
    }

    // a double quoted js string literal that is also safe inside html
    public static String jsString(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20 || c == '<' || c == '>' || c == ' ' || c == ' ') {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
