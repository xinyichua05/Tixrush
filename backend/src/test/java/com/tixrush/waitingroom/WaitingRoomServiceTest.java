package com.tixrush.waitingroom;

import org.springframework.context.annotation.Import;
import com.tixrush.RequiresServices;
import com.tixrush.TestcontainersConfiguration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The waiting room against REAL Redis: line order, admitting from the front, one-time passes. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@RequiresServices
class WaitingRoomServiceTest {

    private static final Long EVENT = 990_000_001L; // no DB row needed: the waiting room only uses Redis

    @Autowired WaitingRoomService waitingRoom;

    @AfterEach
    void cleanUp() {
        waitingRoom.deactivate(EVENT);
        waitingRoom.clear(EVENT, List.of("bot:1", "bot:2", "bot:3", "u:7"));
    }

    @Test
    void lobbyFansAreDrawnByScore_lateFansGoToTheBack_admitTakesTheFront() {
        Map<String, Double> lobby = new LinkedHashMap<>();
        lobby.put("bot:1", 0.90); // random lobby scores in [0,1)
        lobby.put("bot:2", 0.10);
        lobby.put("bot:3", 0.50);
        waitingRoom.addAll(EVENT, lobby);
        waitingRoom.add(EVENT, "u:7", 1 + 1500); // arrived 1.5 s after opening: behind every lobby fan

        assertThat(waitingRoom.size(EVENT)).isEqualTo(4);
        assertThat(waitingRoom.ahead(EVENT, "bot:2")).isZero();
        assertThat(waitingRoom.ahead(EVENT, "u:7")).isEqualTo(3);

        assertThat(waitingRoom.admit(EVENT, 2, Duration.ofSeconds(30))).containsExactly("bot:2", "bot:3");
        assertThat(waitingRoom.ahead(EVENT, "bot:2")).as("admitted = out of the line").isNull();
        assertThat(waitingRoom.ahead(EVENT, "u:7")).isEqualTo(1);
        assertThat(waitingRoom.hasPass(EVENT, "bot:2")).isTrue();
        assertThat(waitingRoom.hasPass(EVENT, "bot:1")).as("still waiting = no pass").isFalse();
    }

    @Test
    void passExpiresOnItsOwn() throws InterruptedException {
        waitingRoom.add(EVENT, "u:7", 0.3);
        waitingRoom.admit(EVENT, 1, Duration.ofSeconds(1));
        assertThat(waitingRoom.hasPass(EVENT, "u:7")).isTrue();
        Thread.sleep(2_100);
        assertThat(waitingRoom.hasPass(EVENT, "u:7")).isFalse();
    }
}
