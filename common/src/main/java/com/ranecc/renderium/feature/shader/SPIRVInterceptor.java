package com.ranecc.renderium.feature.shader;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SPIRVInterceptor {
    private static final SPIRVInterceptor INSTANCE = new SPIRVInterceptor();

    private final ConcurrentHashMap<String, SPIRVModule> modules = new ConcurrentHashMap<>();

    public static SPIRVInterceptor getInstance() { return INSTANCE; }

    public byte[] intercept(byte[] spirv) { return spirv; }

    public int getTotalModules() { return modules.size(); }

    public void registerModule(String passName, SPIRVModule module) {
        if (passName != null && module != null) {
            modules.put(passName, module);
        }
    }

    public SPIRVModule getModule(String passName) {
        return modules.get(passName);
    }

    public Set<String> findUndeclaredOfficialPasses(Set<String> declaredPasses) {
        if (declaredPasses == null || declaredPasses.isEmpty()) {
            return Collections.emptySet();
        }

        Set<String> undeclared = new HashSet<>();
        for (String passName : modules.keySet()) {
            if (!declaredPasses.contains(passName)) {
                undeclared.add(passName);
            }
        }
        return Collections.unmodifiableSet(undeclared);
    }

    public static class SPIRVModule {
        private final byte[] data;
        private final String shaderStage;
        private final String version;

        public SPIRVModule(byte[] data, String shaderStage, String version) {
            this.data = data != null ? data : new byte[0];
            this.shaderStage = shaderStage != null ? shaderStage : "unknown";
            this.version = version != null ? version : "0.0.0";
        }

        public byte[] getData() { return data.clone(); }

        public String getShaderStage() { return shaderStage; }

        public long getSize() { return data.length; }

        public String getVersionString() { return version; }
    }
}
