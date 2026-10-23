package io.livecut.jobs;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/model-calls")
public class ModelCallController {
    private final ModelCallService calls;
    public ModelCallController(ModelCallService calls) { this.calls = calls; }

    @PostMapping("/reserve")
    public ModelCallModels.CallResponse reserve(@RequestBody ModelCallModels.ReserveRequest request) {
        return calls.reserve(request);
    }

    @PostMapping("/{requestId}/update")
    public ModelCallModels.CallResponse update(@PathVariable String requestId, @RequestBody ModelCallModels.UpdateRequest request) {
        return calls.update(requestId, request);
    }
}
