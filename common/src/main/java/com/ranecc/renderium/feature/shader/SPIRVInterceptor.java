package com.ranecc.renderium.feature.shader;

import java.util.Collections;
import java.util.Set;

public class SPIRVInterceptor {
    private static final SPIRVInterceptor INSTANCE = new SPIRVInterceptor();

    public static SPIRVInterceptor getInstance() { return INSTANCE; }

    public byte[] intercept(byte[] spirv) { return spirv; }

    public int getTotalModules() { return 0; }

    public Set<String> findUndeclaredOfficialPasses(Set<String> declaredPasses) {
        return Collections.emptySet();
    }

    public SPIRVModule getModule(String passName) { return null; }

    public static class SPIRVModule {
        public byte[] getData() { return new byte[0]; }

        public String getShaderStage() { return "unknown"; }

        public long getSize() { return 0L; }

        public String getVersionString() { return "0.0.0"; }
    }
}
