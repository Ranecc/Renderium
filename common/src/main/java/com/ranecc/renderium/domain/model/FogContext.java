package com.ranecc.renderium.domain.model;

public final class FogContext {

    public final float[] color = new float[4];
    public float start, end, density;
    public int type;
    public boolean enabled;

    public void reset() {
        color[0]=0;color[1]=0;color[2]=0;color[3]=1;
        start=0; end=1000; density=0;
        type=0; enabled=false;
    }
}
