package com.bvisionry.leadmagnet;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Captures a lead-magnet email request and, when a PDF is configured, emails the
 * visitor the research paper as an attachment.
 *
 * <p>Following the {@code LeadService} pattern, persistence is the source of
 * truth: the lead is always saved, and PDF delivery is best-effort and handed to
 * {@link LeadMagnetDispatcher}, which fetches the asset and sends the mail off
 * the request thread so neither can slow (or, under bot flooding, stall) the
 * visitor's response.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeadMagnetService {

    private final LeadMagnetRequestRepository repository;
    private final LeadMagnetDispatcher dispatcher;

    public UUID submit(CreateLeadMagnetRequest request) {
        LeadMagnetRequest lead = new LeadMagnetRequest();
        lead.setEmail(request.email().trim());
        lead.setSource(request.source());

        LeadMagnetRequest saved = repository.save(lead);
        log.info("Lead-magnet request saved: id={} email={}", saved.getId(), saved.getEmail());

        // Fire-and-forget: fetch the PDF + send the email on the async executor.
        dispatcher.dispatchPdf(saved.getEmail());
        return saved.getId();
    }
}
