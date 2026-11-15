package ai.voicecraft.jobs;

public enum JobStatus {
    QUEUED, RUNNING, DONE, CANCELLED, FAILED;

    public boolean active() {
        return this == QUEUED || this == RUNNING;
    }
}
