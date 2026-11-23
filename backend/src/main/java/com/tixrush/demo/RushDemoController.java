package com.tixrush.demo;

import com.tixrush.common.BadRequestException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Live Rush page (any logged-in user):
 *   POST /api/rush-demo/start  {"scenario":"MEGA|POPULAR|INDIE", "lineStyle":"RANDOM_LOBBY|FASTEST_CLICK"}
 *                                                                -> starts a run (409 if one is running)
 *   POST /api/rush-demo/join                                      -> step into the line yourself
 *   POST /api/rush-demo/stop                                      -> end the current run now
 *   GET  /api/rush-demo                                           -> everything the page draws (polled ~2x/s)
 *
 * Buying itself is the normal POST /api/events/{id}/purchases, which checks your waiting-room pass.
 */
@RestController
@RequestMapping("/api/rush-demo")
public class RushDemoController {

    /** lineStyle is optional: RANDOM_LOBBY (default) or FASTEST_CLICK. */
    public record StartRequest(String scenario, String lineStyle) {
    }

    private final RushDemoService demo;

    public RushDemoController(RushDemoService demo) {
        this.demo = demo;
    }

    @GetMapping
    public RushDemoService.State state(@AuthenticationPrincipal Jwt jwt) {
        return demo.state(userId(jwt));
    }

    @PostMapping("/start")
    public RushDemoService.State start(@RequestBody StartRequest request, @AuthenticationPrincipal Jwt jwt) {
        RushScenario scenario;
        try {
            scenario = RushScenario.valueOf(String.valueOf(request.scenario()).toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Please pick one of the three practice rushes.");
        }
        LineStyle lineStyle;
        try {
            lineStyle = request.lineStyle() == null ? LineStyle.RANDOM_LOBBY
                    : LineStyle.valueOf(request.lineStyle().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Please pick a line style.");
        }
        return demo.start(scenario.settings(lineStyle), userId(jwt));
    }

    /** Stop button: ends the current run immediately so a new one can start. */
    @PostMapping("/stop")
    public RushDemoService.State stop(@AuthenticationPrincipal Jwt jwt) {
        return demo.stop(userId(jwt));
    }

    @PostMapping("/join")
    public RushDemoService.State join(@AuthenticationPrincipal Jwt jwt) {
        return demo.join(userId(jwt));
    }

    private static Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
