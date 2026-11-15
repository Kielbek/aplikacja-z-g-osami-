package ai.voicecraft.jobs;

import ai.voicecraft.project.ProjectService;
import ai.voicecraft.security.CurrentUser;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class JobController {
    private final JobService jobs;
    private final ProjectService projects;
    private final EventHub events;

    public JobController(JobService jobs, ProjectService projects, EventHub events) {
        this.jobs = jobs;
        this.projects = projects;
        this.events = events;
    }

    public record StartJob(List<String> segmentIds) {}

    @PostMapping("/projects/{id}/jobs")
    public JobService.JobDto start(@PathVariable String id, @RequestBody(required = false) StartJob req) {
        return jobs.start(id, req == null ? null : req.segmentIds());
    }

    @GetMapping("/projects/{id}/jobs/active")
    public ResponseEntity<JobService.JobDto> active(@PathVariable String id) {
        var job = jobs.active(id);
        return job == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(job);
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public JobService.JobDto cancel(@PathVariable String jobId) {
        return jobs.cancel(jobId);
    }

    /** Strumień zdarzeń projektu: "job" (postęp), "segment" (wygenerowany fragment), "segment-error". */
    @GetMapping(value = "/projects/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id) {
        projects.get(id); // weryfikuje właściciela
        return events.subscribe(CurrentUser.id(), id);
    }
}
