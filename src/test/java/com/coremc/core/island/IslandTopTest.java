package com.coremc.core.island;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Island top maths: Solos / Duos / Teams categories by team size, most
 * points first, oldest island wins ties.
 */
final class IslandTopTest {

    private static Island island(final String owner, final long createdAt,
                                 final UUID... members) {
        final Island island = new Island(UUID.randomUUID(), UUID.randomUUID(), owner,
                "coremc_islands", 0, 0, 64, 0, 100, "default", createdAt, 0, 64, 0, 0f, 0f);
        for (final UUID member : members) {
            island.addMember(member);
        }
        return island;
    }

    @Test
    void categoriesFollowTeamSize() {
        assertEquals(IslandTop.Category.SOLOS, IslandTop.categoryOf(island("Alone", 1)));
        assertEquals(IslandTop.Category.DUOS,
                IslandTop.categoryOf(island("Duo", 1, UUID.randomUUID())));
        assertEquals(IslandTop.Category.TEAMS,
                IslandTop.categoryOf(island("Trio", 1, UUID.randomUUID(), UUID.randomUUID())));
        assertEquals(IslandTop.Category.TEAMS, IslandTop.categoryOf(
                island("Party", 1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())));
    }

    @Test
    void solosRankByPointsDescending() {
        final Island first = island("Rich", 3);
        final Island second = island("Poor", 2);
        final Island third = island("Broke", 1);
        final java.util.Map<String, Double> board = java.util.Map.of(
                "Rich", 30.0, "Poor", 20.0, "Broke", 10.0);
        final List<Island> top = IslandTop.top(List.of(second, third, first),
                island -> board.get(island.ownerName()), IslandTop.Category.SOLOS, 10);
        assertEquals(List.of("Rich", "Poor", "Broke"),
                top.stream().map(Island::ownerName).toList());
    }

    @Test
    void tiesGoToTheOlderIsland() {
        final Island older = island("Veteran", 100);
        final Island newer = island("Rookie", 200);
        final List<Island> top = IslandTop.top(List.of(newer, older),
                island -> 42.0, IslandTop.Category.SOLOS, 10);
        assertEquals(List.of("Veteran", "Rookie"),
                top.stream().map(Island::ownerName).toList());
    }

    @Test
    void teamsAreKeptApartFromSolosAndDuos() {
        final Island solo = island("Solo", 1);
        final Island duo = island("Duo", 2, UUID.randomUUID());
        final Island team = island("Team", 3, UUID.randomUUID(), UUID.randomUUID());
        final List<Island> all = List.of(solo, duo, team);
        assertEquals(List.of("Team"),
                IslandTop.top(all, island -> 1.0, IslandTop.Category.TEAMS, 10)
                        .stream().map(Island::ownerName).toList());
        assertEquals(List.of("Duo"),
                IslandTop.top(all, island -> 1.0, IslandTop.Category.DUOS, 10)
                        .stream().map(Island::ownerName).toList());
        assertEquals(List.of("Solo"),
                IslandTop.top(all, island -> 1.0, IslandTop.Category.SOLOS, 10)
                        .stream().map(Island::ownerName).toList());
    }

    @Test
    void limitTruncatesTheBoard() {
        final Island a = island("A", 1);
        final Island b = island("B", 2);
        final Island c = island("C", 3);
        final List<Island> top = IslandTop.top(List.of(a, b, c),
                island -> 1.0, IslandTop.Category.SOLOS, 2);
        assertEquals(2, top.size());
    }

    @Test
    void rankOfCountsWithinTheCategoryOnly() {
        final Island soloBest = island("SoloBest", 1);
        final Island soloWorst = island("SoloWorst", 2);
        final Island teamBest = island("TeamBest", 3, UUID.randomUUID(), UUID.randomUUID());
        final List<Island> all = List.of(soloWorst, teamBest, soloBest);
        assertEquals(1, IslandTop.rankOf(soloBest, all, island -> 1.0));
        assertEquals(2, IslandTop.rankOf(soloWorst, all, island -> 1.0));
        assertEquals(1, IslandTop.rankOf(teamBest, all, island -> 1.0));
    }
}
