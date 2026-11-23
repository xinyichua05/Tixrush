package com.tixrush.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the React website from the same server as the API (in the Docker image, the website's
 * built files are packed into static/: index.html, assets/...).
 *
 * The website has its own pages (/events/87, /my-tickets...), but those are React routes, not files.
 * If someone opens or refreshes such a link, the server must answer with index.html and let React
 * show the right page. That's what the "forward" does (inside the server: the URL in the browser
 * stays /events/87).
 *
 * What is NOT forwarded: /api/** and /actuator/** (real endpoints, or a real 404), /visualizer/**,
 * and anything with a dot in the last part (/assets/index-abc.js, /favicon.svg): those are files.
 */
@Controller
public class SpaController {

    private static final String NOT_RESERVED = "(?!api$|actuator$|visualizer$|assets$|error$)[^.]*";

    @GetMapping({"/", "/{page:" + NOT_RESERVED + "}", "/{page:" + NOT_RESERVED + "}/**"})
    public String website() {
        return "forward:/index.html";
    }

    /** The developer Rush Visualizer lives in static/visualizer/ (only reachable with DEV_TOOLS=true). */
    @GetMapping("/visualizer")
    public String visualizerSlash() {
        return "redirect:/visualizer/";
    }

    @GetMapping("/visualizer/")
    public String visualizer() {
        return "forward:/visualizer/index.html";
    }
}
