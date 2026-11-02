package vn.truerate.license;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic licence check (code, not AI).
 * LICENSED only on an exact normalised match. A near match is UNDETERMINED so that a copycat name is never
 * shown as licensed.
 */
@Component
public class LicenseRegistry {

    public enum Status { LICENSED, NOT_FOUND, UNDETERMINED }

    public record Match(Status status, String matchedName, String listVersion, String listSource) {
    }

    private record Entry(String name, List<String> aliases) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final String version;
    private final String source;

    public LicenseRegistry() throws IOException {
        try (InputStream in = LicenseRegistry.class.getResourceAsStream("/licensed-lenders.json")) {
            JsonNode root = new ObjectMapper().readTree(in);
            this.version = root.path("version").asText("unknown");
            this.source = root.path("source").asText("");
            for (JsonNode n : root.path("lenders")) {
                List<String> aliases = new ArrayList<>();
                aliases.add(normalize(n.path("name").asText()));
                for (JsonNode a : n.path("aliases")) {
                    aliases.add(normalize(a.asText()));
                }
                entries.add(new Entry(n.path("name").asText(), aliases));
            }
        }
    }

    public Match check(String lenderName) {
        String q = normalize(lenderName);
        if (q.isEmpty()) {
            return new Match(Status.UNDETERMINED, null, version, source);
        }
        for (Entry e : entries) {
            if (e.aliases().contains(q)) {
                return new Match(Status.LICENSED, e.name(), version, source);
            }
        }
        for (Entry e : entries) {
            for (String alias : e.aliases()) {
                if (isNear(q, alias)) {
                    return new Match(Status.UNDETERMINED, e.name(), version, source);
                }
            }
        }
        return new Match(Status.NOT_FOUND, null, version, source);
    }

    static boolean isNear(String q, String alias) {
        if (alias.length() >= 5 && (q.contains(alias) || (q.length() >= 5 && alias.contains(q)))) {
            return true;
        }
        int max = Math.max(q.length(), alias.length());
        return max > 0 && 1.0 - (double) levenshtein(q, alias) / max >= 0.8;
    }

    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String t = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd');
        t = t.replaceAll("[^a-z0-9]+", " ").trim();
        t = t.replaceAll("\\b(cong ty tai chinh|cong ty|cty|tnhh|mtv|co phan|cp|ngan hang|tmcp|thuong mai)\\b", " ");
        return t.replaceAll("\\s+", " ").trim();
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }
}
