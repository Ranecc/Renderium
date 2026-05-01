package com.ranecc.renderium.domain.model;

public final class QualityScore {

    public final float lyapunovExponent;
    public final float quality;
    public final Confidence confidence;
    public final long frameIndex;

    public QualityScore(float lyapunovExponent, float quality,
                         Confidence confidence, long frameIndex) {
        this.lyapunovExponent = lyapunovExponent;
        this.quality = quality;
        this.confidence = confidence;
        this.frameIndex = frameIndex;
    }

    public boolean isAcceptable() {
        return quality >= 0.5f && confidence != Confidence.LOW;
    }

    public enum Confidence { HIGH, MEDIUM, LOW }
}
