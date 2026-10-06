package com.yanyuetong.dto;

/** 本轮预约请求对账的只读处理摘要。 */
public class ReconcileSummaryDTO {

    private final int scanned;
    private final int confirmed;
    private final int compensated;
    private final int skipped;
    private final int failed;

    public ReconcileSummaryDTO(int scanned, int confirmed, int compensated,
                               int skipped, int failed) {
        this.scanned = scanned;
        this.confirmed = confirmed;
        this.compensated = compensated;
        this.skipped = skipped;
        this.failed = failed;
    }

    public int getScanned() {
        return scanned;
    }

    public int getConfirmed() {
        return confirmed;
    }

    public int getCompensated() {
        return compensated;
    }

    public int getSkipped() {
        return skipped;
    }

    public int getFailed() {
        return failed;
    }
}
