package com.ranecc.renderium.domain.model;

public final class LodResult {

    public final int sectionX, sectionZ;
    public final int lodLevel;
    public final float distance;
    public final float screenCoverage;

    public LodResult(int sectionX, int sectionZ,
                     int lodLevel, float distance,
                     float screenCoverage) {
        this.sectionX = sectionX;
        this.sectionZ = sectionZ;
        this.lodLevel = lodLevel;
        this.distance = distance;
        this.screenCoverage = screenCoverage;
    }
}
