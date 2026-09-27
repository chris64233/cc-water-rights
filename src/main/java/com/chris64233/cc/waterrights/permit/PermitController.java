package com.chris64233.cc.waterrights.permit;

import com.chris64233.cc.waterrights.permit.dto.CreatePermitRequest;
import com.chris64233.cc.waterrights.permit.dto.DeclarationRequest;
import com.chris64233.cc.waterrights.permit.dto.EventResponse;
import com.chris64233.cc.waterrights.permit.dto.LedgerResponse;
import com.chris64233.cc.waterrights.permit.dto.PermitResponse;
import com.chris64233.cc.waterrights.permit.dto.ReversalRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/permits")
public class PermitController {

    private final PermitService permitService;

    public PermitController(PermitService permitService) {
        this.permitService = permitService;
    }

    @PostMapping
    public ResponseEntity<PermitResponse> createPermit(@Valid @RequestBody CreatePermitRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(permitService.createPermit(request));
    }

    @PostMapping("/{permitNo}/declarations")
    public EventResponse declare(@PathVariable String permitNo, @Valid @RequestBody DeclarationRequest request) {
        return permitService.declare(permitNo, request);
    }

    @PostMapping("/{permitNo}/reversals")
    public EventResponse reverse(@PathVariable String permitNo, @Valid @RequestBody ReversalRequest request) {
        return permitService.reverse(permitNo, request);
    }

    @GetMapping("/{permitNo}/ledger")
    public LedgerResponse ledger(@PathVariable String permitNo) {
        return permitService.ledger(permitNo);
    }
}
