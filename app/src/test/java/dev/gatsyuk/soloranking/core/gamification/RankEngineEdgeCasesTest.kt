package dev.gatsyuk.soloranking.core.gamification

import dev.gatsyuk.soloranking.core.database.dao.ExerciseWithMuscles
import dev.gatsyuk.soloranking.core.database.dao.WorkoutExerciseWithSets
import dev.gatsyuk.soloranking.core.database.dao.WorkoutWithContent
import dev.gatsyuk.soloranking.core.database.entity.ExerciseEntity
import dev.gatsyuk.soloranking.core.database.entity.ExerciseMuscleEntity
import dev.gatsyuk.soloranking.core.database.entity.SetEntryEntity
import dev.gatsyuk.soloranking.core.database.entity.WorkoutEntity
import dev.gatsyuk.soloranking.core.database.entity.WorkoutExerciseEntity
import dev.gatsyuk.soloranking.core.model.ExerciseType
import dev.gatsyuk.soloranking.core.model.Muscle
import dev.gatsyuk.soloranking.core.model.MuscleRole
import dev.gatsyuk.soloranking.core.model.Rank
import dev.gatsyuk.soloranking.core.model.SetKind
import dev.gatsyuk.soloranking.core.model.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.time.LocalDate

/**
 * Boundary and degenerate-input cases for [RankEngine]; the happy paths live
 * in RankEngineTest. Rank boundaries use Back Squat singles (e1RM = weight)
 * at 60 kg bodyweight, where the C->B segment is exact in binary floating point.
 */
class RankEngineEdgeCasesTest {

    private val squat = ExerciseEntity(
        id = 1, name = "Back Squat", muscleGroupId = 1,
        exerciseType = ExerciseType.STRENGTH_WEIGHT_REPS,
    )

    private val catalog = listOf(
        ExerciseWithMuscles(
            squat,
            listOf(ExerciseMuscleEntity(1, Muscle.QUADS, MuscleRole.PRIMARY, 1.0)),
        ),
    )

    private var nextSetId = 100L

    private fun set(weight: Double?, reps: Int?, kind: SetKind = SetKind.WORKING) =
        SetEntryEntity(
            id = nextSetId++, workoutExerciseId = 0, position = 0,
            setKind = kind, weightKg = weight, reps = reps,
        )

    private fun workout(
        id: Long,
        date: LocalDate,
        bodyweight: Double? = 60.0,
        sets: List<SetEntryEntity>? = null,
    ) = WorkoutWithContent(
        workout = WorkoutEntity(id = id, name = "W$id", date = date, bodyweightKg = bodyweight),
        exercises = if (sets == null) {
            emptyList()
        } else {
            listOf(
                WorkoutExerciseWithSets(
                    workoutExercise = WorkoutExerciseEntity(
                        id = id * 10, workoutId = id, exerciseId = squat.id, position = 0,
                    ),
                    exercise = squat,
                    sets = sets.mapIndexed { p, s -> s.copy(position = p) },
                ),
            )
        },
    )

    private val today = LocalDate.of(2026, 7, 11)

    private fun single(weight: Double, bodyweight: Double? = 60.0, sex: Sex = Sex.MALE) =
        RankEngine.compute(
            listOf(workout(1, today, bodyweight, listOf(set(weight, 1)))),
            catalog, sex, null, today,
        )

    // --- rank boundaries ---

    @Test
    fun `score exactly on a tier threshold gets the higher tier`() {
        // 100 / 60 kg -> score exactly 50.0 = bottom of B
        val on = single(100.0).exercises.single()
        assertEquals(50.0, on.score!!, 0.0)
        assertEquals(Rank.B, on.rank)
        assertEquals(Rank.B, single(100.0).muscleRanks[Muscle.QUADS]!!.rank)
    }

    @Test
    fun `score just below and just above a tier threshold`() {
        val below = single(99.5).exercises.single()
        assertEquals(49.6875, below.score!!, 1e-9)
        assertEquals(Rank.B_MINUS, below.rank)

        val above = single(100.5).exercises.single()
        assertEquals(50.3125, above.score!!, 1e-9)
        assertEquals(Rank.B, above.rank)
    }

    @Ignore(
        "Bug: 84 kg @ 60 kg is a 1.40x multiple, exactly halfway C(1.2)->B(1.6), so the score " +
            "should be 40.0 (C+). Floating point gives 39.99999999999999 and Rank.fromScore floors " +
            "it to C. Thresholds need an epsilon or exact arithmetic.",
    )
    @Test
    fun `threshold reached through interpolation is not lost to floating point`() {
        val standing = single(84.0).exercises.single()
        assertEquals(Rank.C_PLUS, standing.rank)
    }

    @Test
    fun `world-record multiple gives the top rank and the score is capped`() {
        // Back Squat S anchor 2.5x * 1.35 = 3.375x = 202.5 kg @ 60 kg
        val atWr = single(202.5).exercises.single()
        assertEquals(StrengthStandards.MAX_SCORE, atWr.score!!, 0.0)
        assertEquals(Rank.SSS_PLUS, atWr.rank)

        // just under the WR multiple is still interpolated (~116.6) and already SSS+
        val justUnder = single(202.0).exercises.single()
        assertTrue(justUnder.score!! < StrengthStandards.MAX_SCORE)
        assertEquals(Rank.SSS_PLUS, justUnder.rank)

        // far beyond the WR multiple: capped, never past the last rank
        val beyond = single(400.0).exercises.single()
        assertEquals(StrengthStandards.MAX_SCORE, beyond.score!!, 0.0)
        assertEquals(Rank.SSS_PLUS, beyond.rank)
        assertEquals(Rank.SSS_PLUS, single(400.0).muscleRanks[Muscle.QUADS]!!.rank)
    }

    // --- plausibility limits (inclusive) ---

    @Test
    fun `weight exactly at the plausibility limit counts, just above does not`() {
        val atLimit = single(500.0, bodyweight = 100.0).exercises.single()
        assertEquals(10, atLimit.xp)
        assertEquals(500.0, atLimit.bestE1rmKg!!, 0.0)

        val overLimit = single(500.5, bodyweight = 100.0)
        // the set is skipped entirely: 0 XP, no record, but the standing row exists
        assertEquals(0, overLimit.exercises.single().xp)
        assertNull(overLimit.exercises.single().bestE1rmKg)
        assertNull(overLimit.exercises.single().rank)
    }

    @Test
    fun `reps exactly at the plausibility limit count, just above do not`() {
        val at = RankEngine.compute(
            listOf(workout(1, today, 60.0, listOf(set(20.0, 100)))), catalog, Sex.MALE, null, today,
        )
        assertEquals(10, at.exercises.single().xp)

        val over = RankEngine.compute(
            listOf(workout(1, today, 60.0, listOf(set(20.0, 101)))), catalog, Sex.MALE, null, today,
        )
        assertEquals(0, over.exercises.single().xp)
        assertNull(over.exercises.single().bestE1rmKg)
    }

    // --- level thresholds ---

    /** n sessions of identical 6-set work (no PRs) plus [extraSets] in a final session. */
    private fun volumeOnly(fullSessions: Int, extraSets: Int): List<WorkoutWithContent> {
        val sessions = (1..fullSessions).map { i ->
            workout(i.toLong(), today.minusDays(i.toLong()), 60.0, List(6) { set(50.0, 5) })
        }
        val extra = if (extraSets > 0) {
            listOf(workout(99, today, 60.0, List(extraSets) { set(50.0, 5) }))
        } else {
            emptyList()
        }
        return sessions + extra
    }

    @Test
    fun `exercise level boundary at 100 xp - below, on, above`() {
        val below = RankEngine.compute(volumeOnly(1, 3), catalog, Sex.MALE, null, today)
        assertEquals(90, below.exercises.single().xp)
        assertEquals(RankEngine.LevelInfo(level = 1, xpInto = 90, xpForNext = 100), below.exercises.single().level)

        val on = RankEngine.compute(volumeOnly(1, 4), catalog, Sex.MALE, null, today)
        assertEquals(100, on.exercises.single().xp)
        assertEquals(RankEngine.LevelInfo(level = 2, xpInto = 0, xpForNext = 200), on.exercises.single().level)

        val above = RankEngine.compute(volumeOnly(1, 5), catalog, Sex.MALE, null, today)
        assertEquals(110, above.exercises.single().xp)
        assertEquals(RankEngine.LevelInfo(level = 2, xpInto = 10, xpForNext = 200), above.exercises.single().level)
    }

    @Test
    fun `overall level boundary at 500 xp - below, on, above`() {
        fun emptyWorkouts(n: Int) = (1..n).map { workout(it.toLong(), today.minusDays(it.toLong()), 60.0) }

        val below = RankEngine.compute(emptyWorkouts(9), catalog, Sex.MALE, null, today)
        assertEquals(450, below.overallXp)
        assertEquals(RankEngine.LevelInfo(level = 1, xpInto = 450, xpForNext = 500), below.overallLevel)

        val on = RankEngine.compute(emptyWorkouts(10), catalog, Sex.MALE, null, today)
        assertEquals(500, on.overallXp)
        assertEquals(RankEngine.LevelInfo(level = 2, xpInto = 0, xpForNext = 1000), on.overallLevel)

        val above = RankEngine.compute(emptyWorkouts(11), catalog, Sex.MALE, null, today)
        assertEquals(550, above.overallXp)
        assertEquals(RankEngine.LevelInfo(level = 2, xpInto = 50, xpForNext = 1000), above.overallLevel)
    }

    @Test
    fun `session cap clips a PR-heavy session to exactly 200`() {
        // heaviest e1RM first, so session 1 itself pays no in-session PR
        val w1 = workout(1, today.minusWeeks(1), 60.0, (4 downTo 1).map { set(100.0, it) })
        val w2 = workout(2, today, 60.0, (1..4).map { set(110.0, it) })
        val state = RankEngine.compute(listOf(w1, w2), catalog, Sex.MALE, null, today)
        // session 2: 40 (sets) + 100 (e1RM PR) + 75 (rep PRs, capped) = 215 -> 200
        assertEquals(40 + 200, state.exercises.single().xp)
        // the 4th rep PR is past the rep-PR XP cap and is not counted as an event
        assertEquals(4, state.totalPrCount)
    }

    // --- empty data ---

    @Test
    fun `empty history and empty catalog give a clean zero state`() {
        val state = RankEngine.compute(emptyList(), emptyList(), Sex.MALE, null, today)
        assertEquals(0, state.overallXp)
        assertEquals(RankEngine.LevelInfo(level = 1, xpInto = 0, xpForNext = 500), state.overallLevel)
        assertNull(state.glPoints)
        assertNull(state.overallRank)
        assertEquals(RankEngine.StatSheet(strengthGl = null, enduranceReps28d = 0, consistencyPct = 0), state.statSheet)
        assertEquals(0, state.currentStreakWeeks)
        assertEquals(0, state.longestStreakWeeks)
        assertTrue(state.exercises.isEmpty())
        assertTrue(state.muscleRanks.isEmpty())
        assertEquals(0, state.totalPrCount)
    }

    @Test
    fun `workout with no exercises still pays workout xp and counts for the streak`() {
        val state = RankEngine.compute(listOf(workout(1, today, 60.0)), catalog, Sex.MALE, null, today)
        assertEquals(50, state.overallXp)
        assertTrue(state.exercises.isEmpty())
        assertEquals(1, state.currentStreakWeeks)
    }

    @Test
    fun `exercise with only warmups appears with zero xp at level 1`() {
        val w = workout(1, today, 60.0, listOf(set(100.0, 5, kind = SetKind.WARMUP)))
        val standing = RankEngine.compute(listOf(w), catalog, Sex.MALE, null, today).exercises.single()
        assertEquals(0, standing.xp)
        assertEquals(RankEngine.LevelInfo(level = 1, xpInto = 0, xpForNext = 100), standing.level)
        assertNull(standing.bestE1rmKg)
        assertNull(standing.rank)
    }

    @Test
    fun `missing profile data leaves everything unranked`() {
        // no bodyweight anywhere
        val noBw = single(100.0, bodyweight = null)
        assertNull(noBw.exercises.single().score)
        assertNull(noBw.exercises.single().rank)
        assertTrue(noBw.muscleRanks.isEmpty())

        // sex not set
        val noSex = single(100.0, sex = Sex.UNSET)
        assertNull(noSex.exercises.single().rank)
        assertTrue(noSex.muscleRanks.isEmpty())

        // zero bodyweight is treated as missing, not as infinite strength
        assertNull(single(100.0, bodyweight = 0.0).exercises.single().rank)
    }

    @Test
    fun `profile fallback bodyweight is used when workouts carry none`() {
        val w = workout(1, today, null, listOf(set(100.0, 1)))
        val state = RankEngine.compute(listOf(w), catalog, Sex.MALE, 60.0, today)
        assertEquals(Rank.B, state.exercises.single().rank)
    }

    // --- zero and negative values ---

    @Test
    fun `zero weight earns set xp but sets no record and no rank`() {
        val w = workout(1, today, 60.0, listOf(set(0.0, 5)))
        val state = RankEngine.compute(listOf(w), catalog, Sex.MALE, null, today)
        val standing = state.exercises.single()
        assertEquals(10, standing.xp)
        assertNull(standing.bestE1rmKg)
        assertNull(standing.rank)
        assertEquals(5, state.statSheet.enduranceReps28d)
    }

    @Test
    fun `zero reps set no record`() {
        val w = workout(1, today, 60.0, listOf(set(100.0, 0)))
        val standing = RankEngine.compute(listOf(w), catalog, Sex.MALE, null, today).exercises.single()
        assertNull(standing.bestE1rmKg)
        assertNull(standing.rank)
    }

    @Test
    fun `negative weight sets no record and pays no PR`() {
        val w1 = workout(1, today.minusWeeks(1), 60.0, listOf(set(50.0, 5)))
        val w2 = workout(2, today, 60.0, listOf(set(-200.0, 5)))
        val state = RankEngine.compute(listOf(w1, w2), catalog, Sex.MALE, null, today)
        assertEquals(0, state.totalPrCount)
        assertEquals(50.0 * (1 + 5 / 30.0), state.exercises.single().bestE1rmKg!!, 1e-9)
    }

    @Ignore(
        "Bug: a set with negative reps (the reps field accepts \"-5\" via toIntOrNull) is counted " +
            "as a working set (+10 XP) and its reps are summed into enduranceReps28d, so the stat goes " +
            "down. Negative reps should be treated like implausible input.",
    )
    @Test
    fun `negative reps earn nothing and do not reduce endurance`() {
        val w = workout(1, today, 60.0, listOf(set(50.0, 10), set(50.0, -5)))
        val state = RankEngine.compute(listOf(w), catalog, Sex.MALE, null, today)
        assertEquals(10, state.exercises.single().xp)
        assertEquals(10, state.statSheet.enduranceReps28d)
    }

    // --- streak boundaries across a year ---

    @Test
    fun `streak survives the year rollover`() {
        // Locale.US weeks: 2025-12-24 = 2025-W52, 2025-12-31 = 2026-W1, 2026-01-07 = 2026-W2
        val dates = listOf(LocalDate.of(2025, 12, 24), LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 7))
        val history = dates.mapIndexed { i, d -> workout(i + 1L, d, 60.0) }
        val state = RankEngine.compute(history, catalog, Sex.MALE, null, LocalDate.of(2026, 1, 7))
        assertEquals(3, state.currentStreakWeeks)
        assertEquals(3, state.longestStreakWeeks)
    }

    @Ignore(
        "Bug: consecutiveWeeks() accepts week 52 -> week 1 of the next year even when that year has " +
            "a week 53 (Locale.US week-based year 2022, also 2028 and 2033), so skipping week 53 does " +
            "not break the streak.",
    )
    @Test
    fun `skipping week 53 breaks the streak`() {
        // 2022-12-18 = 2022-W52, 2022-12-25..31 = 2022-W53 (skipped), 2023-01-01 = 2023-W1
        val history = listOf(
            workout(1, LocalDate.of(2022, 12, 18), 60.0),
            workout(2, LocalDate.of(2023, 1, 1), 60.0),
        )
        val state = RankEngine.compute(history, catalog, Sex.MALE, null, LocalDate.of(2023, 1, 1))
        assertEquals(1, state.longestStreakWeeks)
    }
}
