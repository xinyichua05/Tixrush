package com.tixrush.order;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.tixrush.common.BadRequestException;
import com.tixrush.common.ConflictException;
import com.tixrush.common.NotFoundException;
import com.tixrush.event.EventRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Used by the visualizer page (ADMIN only, via the /api/admin/** rule).
 *   POST /api/admin/events/{id}/rush?strategy=redis&buyers=2000&concurrency=100  -> 202, starts a rush
 *   GET  /api/admin/rush                                                          -> live progress
 */
/** Developer tool: only exists when tixrush.dev-tools.enabled=true. */
@ConditionalOnProperty(name = "tixrush.dev-tools.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/admin")
public class RushSimulationController {

    private final RushSimulationService rushService;
    private final PurchaseService purchaseService;
    private final EventRepository eventRepository;

    public RushSimulationController(RushSimulationService rushService, PurchaseService purchaseService,
                                    EventRepository eventRepository) {
        this.rushService = rushService;
        this.purchaseService = purchaseService;
        this.eventRepository = eventRepository;
    }

    @PostMapping("/events/{eventId}/rush")
    @ResponseStatus(HttpStatus.ACCEPTED) // 202 = "started, not finished yet"
    public RushSimulationService.RushProgress start(@PathVariable Long eventId,
                                                    @RequestParam(defaultValue = PurchaseService.DEFAULT_STRATEGY) String strategy,
                                                    @RequestParam(defaultValue = "2000") int buyers,
                                                    @RequestParam(defaultValue = "100") int concurrency) {
        if (!purchaseService.hasStrategy(strategy)) {
            throw new BadRequestException("Unknown strategy '" + strategy + "'. Use one of " + purchaseService.strategyNames());
        }
        if (buyers < 1 || buyers > 20_000) {
            throw new BadRequestException("buyers must be between 1 and 20000");
        }
        if (concurrency < 1 || concurrency > 200) {
            throw new BadRequestException("concurrency must be between 1 and 200");
        }
        if (!eventRepository.existsById(eventId)) {
            throw new NotFoundException("Event " + eventId + " not found");
        }
        if (!rushService.start(eventId, strategy, buyers, concurrency)) {
            throw new ConflictException("A rush is already running. Wait for it to finish.");
        }
        return rushService.progress();
    }

    @GetMapping("/rush")
    public RushSimulationService.RushProgress progress() {
        return rushService.progress();
    }
}
