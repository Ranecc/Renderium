package com.ranecc.renderium.domain.enums;

public enum QualityLevel {
    ULTRA(4),
    HIGH(3),
    MEDIUM(2),
    LOW(1);

    public final int value;

    QualityLevel(int value) { this.value = value; }
}
