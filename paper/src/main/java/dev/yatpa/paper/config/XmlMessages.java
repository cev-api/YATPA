package dev.yatpa.paper.config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;
import org.bukkit.ChatColor;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class XmlMessages {
    private final Map<String, String> messages = new HashMap<>();

    public void load(File file) {
        messages.clear();
        try {
            String raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            String sanitized = raw.replaceAll("&(?![a-zA-Z#][a-zA-Z0-9]*;)", "&amp;");
            InputSource source = new InputSource(new java.io.StringReader(sanitized));
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(source);
            NodeList nodes = doc.getElementsByTagName("message");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element element = (Element) nodes.item(i);
                String key = element.getAttribute("key");
                if (key != null && !key.isBlank()) {
                    messages.put(key, ChatColor.translateAlternateColorCodes('&', element.getTextContent()));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load messages.xml", e);
        }
    }

    public String get(String key) {
        return messages.getOrDefault(key, key);
    }

    public String format(String key, Map<String, String> replacements) {
        String text = get(key);
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            text = text.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        return text;
    }
}
