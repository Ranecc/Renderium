package com.ranecc.renderium.feature.shader.spirv;

import java.util.Collections;
import java.util.List;

public class SPIRVShaderModule {
    private final byte[] spirvBinary;
    private final String entryPoint;
    private final int stage;

    public static class SpirvReference {
        public String path;
        public String entryPoint;
    }

    public SPIRVShaderModule(byte[] spirvBinary, String entryPoint, int stage) {
        this.spirvBinary = spirvBinary != null ? spirvBinary.clone() : new byte[0];
        this.entryPoint = entryPoint;
        this.stage = stage;
    }

    public byte[] getSpirvBinary() { return spirvBinary != null ? spirvBinary.clone() : new byte[0]; }

    public String getEntryPoint() { return entryPoint; }

    public int getStage() { return stage; }

    public int getSize() { return spirvBinary != null ? spirvBinary.length : 0; }

    public boolean isValid() { return spirvBinary != null && spirvBinary.length > 0; }

    public static SPIRVShaderModule load(SpirvReference ref) {
        return new SPIRVShaderModule(new byte[0], ref != null ? ref.entryPoint : "main", 0);
    }

    public List<String> getUniforms() { return Collections.emptyList(); }
}
