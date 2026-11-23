package com.tixrush.order;

import com.tixrush.common.BadRequestException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Picks which "Buy" version to run.
 *
 * Spring finds EVERY bean that implements PurchaseStrategy and passes them all in as a List.
 * We index them by name: {"naive" -> ..., "pessimistic" -> ..., "optimistic" -> ...}.
 */
@Service
public class PurchaseService {

    public static final String DEFAULT_STRATEGY = "pessimistic";

    private final Map<String, PurchaseStrategy> strategies;

    public PurchaseService(List<PurchaseStrategy> strategies) {
        this.strategies = strategies.stream()
                .collect(Collectors.toMap(PurchaseStrategy::name, Function.identity()));
    }

    public boolean hasStrategy(String strategyName) {
        return strategyName != null && strategies.containsKey(strategyName.toLowerCase());
    }

    public Set<String> strategyNames() {
        return strategies.keySet();
    }

    public OrderResponse purchase(String strategyName, Long eventId, Long userId) {
        PurchaseStrategy strategy = strategies.get(strategyName.toLowerCase());
        if (strategy == null) {
            throw new BadRequestException("Unknown strategy '" + strategyName + "'. Use one of " + strategies.keySet());
        }
        return strategy.purchase(eventId, userId);
    }
}
