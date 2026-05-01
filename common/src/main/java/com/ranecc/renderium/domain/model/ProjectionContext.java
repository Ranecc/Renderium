package com.ranecc.renderium.domain.model;

public final class ProjectionContext {

    public final float[] matrix = new float[16];
    public float fov;
    public float nearPlane;
    public float farPlane;

    public void reset() {
        identity(matrix);
        fov = 70.0f;
        nearPlane = 0.05f;
        farPlane = 1000.0f;
    }

    private static void identity(float[] m) {
        m[0]=1;m[4]=0;m[8]=0;m[12]=0;
        m[1]=0;m[5]=1;m[9]=0;m[13]=0;
        m[2]=0;m[6]=0;m[10]=1;m[14]=0;
        m[3]=0;m[7]=0;m[11]=0;m[15]=1;
    }
}
