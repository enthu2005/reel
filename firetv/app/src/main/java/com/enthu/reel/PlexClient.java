package com.enthu.reel;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.StringReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

public class PlexClient {
    private static final String TAG = "PlexClient";

    public static class PinResponse {
        public int id;
        public String code;
    }

    public static String clientId() {
        String id = Prefs.getClientId();
        if (id == null || id.isEmpty()) {
            id = "reel-firetv-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            Prefs.setClientId(id);
        }
        return id;
    }

    public static PinResponse createPin() throws Exception {
        URL url = new URL("https://plex.tv/api/v2/pins?strong=false");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("X-Plex-Client-Identifier", clientId());
        conn.setRequestProperty("X-Plex-Product", "Reel");
        conn.setRequestProperty("X-Plex-Version", "0.1");
        conn.setRequestProperty("X-Plex-Platform", "Android");
        conn.setRequestProperty("X-Plex-Device", "Fire TV");
        conn.setRequestProperty("X-Plex-Device-Name", "Reel for Fire TV");
        conn.setDoOutput(false);

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new RuntimeException("plex.tv returned HTTP " + code);
        }

        String raw = readAll(conn);
        JSONObject root = new JSONObject(raw);
        PinResponse pin = new PinResponse();
        pin.id = root.optInt("id");
        pin.code = root.optString("code", "");
        return pin;
    }

    public static String pollPin(int pinId) throws Exception {
        URL url = new URL("https://plex.tv/api/v2/pins/" + pinId);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("X-Plex-Client-Identifier", clientId());
        conn.setRequestProperty("X-Plex-Product", "Reel");
        conn.setRequestProperty("X-Plex-Version", "0.1");
        conn.setRequestProperty("X-Plex-Platform", "Android");
        conn.setRequestProperty("X-Plex-Device", "Fire TV");
        conn.setRequestProperty("X-Plex-Device-Name", "Reel for Fire TV");

        int status = conn.getResponseCode();
        if (status == 404) return null;
        if (status < 200 || status >= 300) {
            throw new RuntimeException("plex.tv poll returned HTTP " + status);
        }

        String raw = readAll(conn);
        if (raw == null || raw.trim().isEmpty()) return null;

        JSONObject root = new JSONObject(raw);
        String token = null;
        if (!root.isNull("authToken")) token = root.optString("authToken", null);
        if ((token == null || token.trim().isEmpty()) && !root.isNull("token")) {
            token = root.optString("token", null);
        }
        if (token == null || token.trim().isEmpty()) return null;
        return token;
    }

    public static String findServer(String token) throws Exception {
        URL url = new URL("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("X-Plex-Token", token);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("X-Plex-Client-Identifier", clientId());
        conn.setRequestProperty("X-Plex-Product", "Reel");
        conn.setRequestProperty("X-Plex-Version", "0.1");
        conn.setRequestProperty("X-Plex-Platform", "Android");
        conn.setRequestProperty("X-Plex-Device", "Fire TV");
        conn.setRequestProperty("X-Plex-Device-Name", "Reel for Fire TV");

        String raw = readAll(conn);
        String uri = parseResourceUri(raw);
        if (uri == null || uri.isEmpty()) {
            throw new RuntimeException("No Plex server found for this account");
        }
        return uri;
    }

    private static String parseResourceUri(String raw) throws Exception {
        if (raw == null || raw.trim().isEmpty()) return null;
        String text = raw.trim();

        if (text.startsWith("[") || text.startsWith("{")) {
            Object parsed;
            try {
                parsed = new JSONArray(text);
            } catch (Exception ignored) {
                parsed = new JSONObject(text);
            }
            return extractUriFromJson(parsed);
        }

        if (text.startsWith("<?xml") || text.startsWith("<")) {
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new InputSource(new StringReader(text)));
            NodeList servers = document.getElementsByTagName("Server");
            NodeList devices = document.getElementsByTagName("Device");
            NodeList all = mergeNodeLists(servers, devices);

            for (int i = 0; i < all.getLength(); i++) {
                Node node = all.item(i);
                if (!(node instanceof Element)) continue;
                Element element = (Element) node;
                NodeList connections = element.getElementsByTagName("Connection");
                for (int j = 0; j < connections.getLength(); j++) {
                    Element conn = (Element) connections.item(j);
                    String protocol = conn.getAttribute("protocol");
                    String address = conn.getAttribute("address");
                    String port = conn.getAttribute("port");
                    String uri = conn.getAttribute("uri");
                    if (!uri.isEmpty()) return uri;
                    if (!address.isEmpty() && !port.isEmpty()) {
                        String scheme = protocol == null || protocol.isEmpty() ? "https" : protocol;
                        return scheme + "://" + address + ":" + port;
                    }
                }
                String uri = element.getAttribute("uri");
                if (!uri.isEmpty()) return uri;
            }
        }

        return null;
    }

    private static String extractUriFromJson(Object value) {
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                String uri = extractUriFromJson(array.opt(i));
                if (uri != null && !uri.isEmpty()) return uri;
            }
            return null;
        }

        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;

            String uri = object.optString("uri", "");
            if (!uri.isEmpty()) return uri;

            String address = object.optString("address", "");
            String port = object.optString("port", "");
            if (!address.isEmpty() && !port.isEmpty()) {
                String protocol = object.optString("protocol", "https");
                return protocol + "://" + address + ":" + port;
            }

            String[] keys = {"connections", "Connection", "Server", "Device", "Directory", "MediaContainer"};
            for (String key : keys) {
                Object child = object.opt(key);
                if (child != null) {
                    String childUri = extractUriFromJson(child);
                    if (childUri != null && !childUri.isEmpty()) return childUri;
                }
            }
        }

        return null;
    }

    private static NodeList mergeNodeLists(NodeList first, NodeList second) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < first.getLength(); i++) out.put(first.item(i));
        for (int i = 0; i < second.getLength(); i++) out.put(second.item(i));
        return new NodeList() {
            @Override
            public Node item(int index) {
                return (Node) out.opt(index);
            }

            @Override
            public int getLength() {
                return out.length();
            }
        };
    }

    public static List<PlexSection> fetchSections(String serverUrl, String token) throws Exception {
        String url = serverUrl + "/library/sections?X-Plex-Token=" + token;
        JSONObject root = new JSONObject(getJson(url, token));
        JSONObject container = root.optJSONObject("MediaContainer");
        JSONArray dirs = container != null ? container.optJSONArray("Directory") : null;
        ArrayList<PlexSection> out = new ArrayList<>();
        if (dirs == null) return out;
        for (int i = 0; i < dirs.length(); i++) {
            JSONObject item = dirs.getJSONObject(i);
            out.add(new PlexSection(
                    item.optString("key", ""),
                    item.optString("title", "Library"),
                    item.optString("type", "movie")
            ));
        }
        return out;
    }

    public static List<PlexItem> fetchLibrary(String serverUrl, String token, String sectionKey) throws Exception {
        String url = serverUrl + "/library/sections/" + sectionKey + "/all?X-Plex-Token=" + token;
        JSONObject root = new JSONObject(getJson(url, token));
        JSONObject container = root.optJSONObject("MediaContainer");
        JSONArray vids = container != null ? container.optJSONArray("Metadata") : null;
        ArrayList<PlexItem> out = new ArrayList<>();
        if (vids == null) return out;
        for (int i = 0; i < vids.length(); i++) {
            JSONObject item = vids.getJSONObject(i);
            out.add(new PlexItem(
                    item.optString("ratingKey", ""),
                    item.optString("title", "Untitled"),
                    item.optString("type", "movie"),
                    item.optString("summary", ""),
                    item.optString("thumb", ""),
                    item.optString("art", ""),
                    item.optInt("year", 0)
            ));
        }
        return out;
    }

    public static List<PlexItem> search(String serverUrl, String token, String query) throws Exception {
        String encodedQuery = java.net.URLEncoder.encode(query, StandardCharsets.UTF_8.name());
        String url = serverUrl + "/hubs/search?query=" + encodedQuery + "&X-Plex-Token=" + token;
        JSONObject root = new JSONObject(getJson(url, token));
        JSONObject container = root.optJSONObject("MediaContainer");
        JSONArray hubs = container != null ? container.optJSONArray("Hub") : null;
        ArrayList<PlexItem> out = new ArrayList<>();
        if (hubs == null) return out;
        for (int i = 0; i < hubs.length(); i++) {
            JSONArray items = hubs.getJSONObject(i).optJSONArray("Metadata");
            if (items == null) continue;
            for (int j = 0; j < items.length(); j++) {
                JSONObject item = items.getJSONObject(j);
                out.add(new PlexItem(
                        item.optString("ratingKey", ""),
                        item.optString("title", "Untitled"),
                        item.optString("type", "movie"),
                        item.optString("summary", ""),
                        item.optString("thumb", ""),
                        item.optString("art", ""),
                        item.optInt("year", 0)
                ));
            }
        }
        return out;
    }

    public static String getStreamUrl(String serverUrl, String token, String ratingKey) {
        return serverUrl + "/library/metadata/" + ratingKey + "/stream?X-Plex-Token=" + token;
    }

    private static String getJson(String urlString, String token) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        if (token != null && !token.isEmpty()) {
            conn.setRequestProperty("X-Plex-Token", token);
        }
        return readAll(conn);
    }

    private static String readAll(HttpURLConnection conn) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }
}
