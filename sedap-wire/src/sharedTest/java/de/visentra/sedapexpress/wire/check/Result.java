package de.visentra.sedapexpress.wire.check;

/** Outcome of one check. Used by the JVM tests and by the on-device probe. */
public final class Result {

    public enum Status { PASS, FAIL, SKIP }

    public final String id;
    public final Status status;
    public final String detail;

    Result(String id, Status status, String detail) {
        this.id = id;
        this.status = status;
        this.detail = detail;
    }

    @Override
    public String toString() {
        return this.status + " " + this.id + (this.detail.isEmpty() ? "" : " | " + this.detail);
    }
}
