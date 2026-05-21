package com.ranecc.renderium.feature.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class RenderiumConfigLoader {

    private static final Logger LOGGER = Logger.getLogger("Renderium|ConfigLoader");

    private static volatile RenderiumConfigLoader INSTANCE;

    private final Map<String, String> rawValues = new ConcurrentHashMap<>();
    private volatile String currentSection = "global";
    private volatile boolean loaded = false;
    private volatile long lastModified = 0L;

    private RenderiumConfigLoader() {}

    public static synchronized RenderiumConfigLoader getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new RenderiumConfigLoader();
        }
        return INSTANCE;
    }

    public void loadDefault() {
        loadFromResource("/config/renderium.yaml");
        loaded = true;
        LOGGER.info(String.format("配置加载完成: %d 个参数, 当前节: [%s]", rawValues.size(), currentSection));
    }

    public void loadFromResource(String resourcePath) {
        try (InputStream is = RenderiumConfigLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                LOGGER.warning("配置文件未找到: " + resourcePath);
                return;
            }
            parse(new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            LOGGER.warning("配置文件读取异常: " + e.getMessage());
        }
    }

    void parse(BufferedReader reader) throws IOException {
        Map<String, String> parsed = new HashMap<>();
        String line;
        int lineNumber = 0;

        while ((line = reader.readLine()) != null) {
            lineNumber++;
            String trimmed = line.trim();

            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.substring(1, trimmed.length() - 1).trim().toLowerCase();
                continue;
            }

            int colonIdx = trimmed.indexOf(':');
            if (colonIdx > 0) {
                String key = trimmed.substring(0, colonIdx).trim().toLowerCase();
                String value = trimmed.substring(colonIdx + 1).trim();
                String fullKey = currentSection + "." + key;
                parsed.put(fullKey, value);
                parsed.put(key, value);
            } else {
                LOGGER.fine("忽略无法解析的行 #" + lineNumber + ": " + trimmed);
            }
        }

        rawValues.putAll(parsed);
    }

    public String getString(String key, String defaultValue) {
        return rawValues.getOrDefault(key.toLowerCase(), defaultValue);
    }

    public float getFloat(String key, float defaultValue) {
        String val = rawValues.get(key.toLowerCase());
        if (val == null) return defaultValue;
        try {
            return Float.parseFloat(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public double getDouble(String key, double defaultValue) {
        String val = rawValues.get(key.toLowerCase());
        if (val == null) return defaultValue;
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public int getInt(String key, int defaultValue) {
        String val = rawValues.get(key.toLowerCase());
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String val = rawValues.get(key.toLowerCase());
        if (val == null) return defaultValue;
        switch (val.toLowerCase()) {
            case "true": case "yes": case "1": case "on":
                return true;
            case "false": case "no": case "0": case "off":
                return false;
            default:
                return defaultValue;
        }
    }

    public boolean isLoaded() { return loaded; }

    public Map<String, String> getAllValues() {
        return Collections.unmodifiableMap(rawValues);
    }

    public Map<String, String> getSection(String sectionName) {
        String prefix = sectionName.toLowerCase() + ".";
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> entry : rawValues.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(prefix)) {
                result.put(key.substring(prefix.length()), entry.getValue());
            }
        }
        return result;
    }

    public static class SectionReader {
        private final String sectionPrefix;
        private final RenderiumConfigLoader loader;

        SectionReader(String sectionName, RenderiumConfigLoader loader) {
            this.sectionPrefix = sectionName.toLowerCase() + ".";
            this.loader = loader;
        }

        public String getString(String key, String def) {
            return loader.getString(sectionPrefix + key, def);
        }

        public float getFloat(String key, float def) {
            return loader.getFloat(sectionPrefix + key, def);
        }

        public double getDouble(String key, double def) {
            return loader.getDouble(sectionPrefix + key, def);
        }

        public int getInt(String key, int def) {
            return loader.getInt(sectionPrefix + key, def);
        }

        public boolean getBoolean(String key, boolean def) {
            return loader.getBoolean(sectionPrefix + key, def);
        }
    }

    public SectionReader section(String name) {
        return new SectionReader(name, this);
    }

    public void reload() {
        rawValues.clear();
        currentSection = "global";
        loaded = false;
        loadDefault();
    }

    /**
     * 设置原始配置值
     *
     * @param key 配置键（自动转小写）
     * @param value 配置值
     */
    public void setRawValue(String key, String value) {
        if (key == null || value == null) return;
        rawValues.put(key.toLowerCase(), value);
    }
}
