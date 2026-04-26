package com.renderium.config;

public enum TextureFilteringMethod {
    NEAREST("nearest"),
    BILINEAR("bilinear"),
    TRILINEAR("trilinear"),
    ANISOTROPIC("anisotropic");

    private final String name;

    TextureFilteringMethod(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
