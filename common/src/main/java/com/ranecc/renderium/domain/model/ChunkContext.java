package com.ranecc.renderium.domain.model;

public final class ChunkContext {

    public int visibleSectionCount;
    public int totalSectionCount;
    public int opaqueDrawCallCount;
    public int translucentDrawCallCount;
    public boolean viewAreaChanged;

    public void reset() {
        visibleSectionCount = 0; totalSectionCount = 0;
        opaqueDrawCallCount = 0; translucentDrawCallCount = 0;
        viewAreaChanged = false;
    }
}
