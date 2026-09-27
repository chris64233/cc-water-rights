package com.chris64233.cc.waterrights.api;

import com.chris64233.cc.waterrights.service.PermitLedgerService;
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

    private final PermitLedgerService ledgerService;

    public PermitController(PermitLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @PostMapping
    public ResponseEntity<PermitResponse> create(@Valid @RequestBody CreatePermitRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ledgerService.createPermit(request));
    }

    @GetMapping("/{permitNo}")
    public PermitResponse ledger(@PathVariable String permitNo) {
        return ledgerService.getLedger(permitNo);
    }

    @PostMapping("/{permitNo}/declarations")
    public ResponseEntity<EventResult> declare(@PathVariable String permitNo,
                                               @Valid @RequestBody DeclareUsageRequest request) {
        EventResult result = ledgerService.declare(permitNo, request);
        return result.replayed() ? ResponseEntity.ok(result)
                : ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PostMapping("/{permitNo}/reversals")
    public ResponseEntity<EventResult> reverse(@PathVariable String permitNo,
                                               @Valid @RequestBody CreateReversalRequest request) {
        EventResult result = ledgerService.reverse(permitNo, request);
        return result.replayed() ? ResponseEntity.ok(result)
                : ResponseEntity.status(HttpStatus.CREATED).body(result);
    }
}
