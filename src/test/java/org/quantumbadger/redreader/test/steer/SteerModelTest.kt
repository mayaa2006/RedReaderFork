/*******************************************************************************
 * This file is part of RedReader.
 *
 * RedReader is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * RedReader is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with RedReader.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/

package org.quantumbadger.redreader.test.steer

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quantumbadger.redreader.steer.KeywordRule
import org.quantumbadger.redreader.steer.PostFeatures
import org.quantumbadger.redreader.steer.Signal
import org.quantumbadger.redreader.steer.SteerConfig
import org.quantumbadger.redreader.steer.SteerLearner
import org.quantumbadger.redreader.steer.SteerRanker
import org.quantumbadger.redreader.steer.SubredditRule

class SteerModelTest {

	private val now = 1_800_000_000_000L
	private val hour = 3_600_000L

	private fun post(
		id: String,
		sub: String = "pics",
		title: String = "A post",
		score: Int = 100,
		ageHours: Long = 2
	) = PostFeatures(id, sub, title, null, null, score, 10, now - ageHours * hour)

	@Test
	fun subredditWeightReordersPosts() {
		val cfg = SteerConfig(redditOrderTrust = 0.0)
			.withRule(SubredditRule("skyrimmods", manualWeight = 3.0))
		val ranked = SteerRanker.rank(
			cfg,
			listOf(post("a", "pics"), post("b", "SkyrimMods")),
			now
		)
		assertEquals("b", ranked.first().post.id)
	}

	@Test
	fun zeroWeightHidesSubreddit() {
		val cfg = SteerConfig().withRule(SubredditRule("pics", manualWeight = 0.0))
		val ranked = SteerRanker.rank(cfg, listOf(post("a", "pics")), now)
		assertTrue(ranked.single().hidden)
	}

	@Test
	fun keywordMuteHidesAndBoostRaises() {
		val cfg = SteerConfig(
			redditOrderTrust = 0.0,
			keywords = listOf(
				KeywordRule("spoiler", mute = true),
				KeywordRule("enb", weight = 3.0)
			)
		)
		val ranked = SteerRanker.rank(
			cfg,
			listOf(
				post("a", title = "Normal post"),
				post("b", title = "My ENB preset"),
				post("c", title = "Big SPOILER inside")
			),
			now
		)
		assertEquals("b", ranked.first().post.id)
		assertTrue(ranked.first { it.post.id == "c" }.hidden)
		assertFalse(ranked.first { it.post.id == "a" }.hidden)
	}

	@Test
	fun freshnessSliderFavoursNewPosts() {
		val old = post("old", score = 20000, ageHours = 20)
		val fresh = post("fresh", score = 50, ageHours = 1)

		val popular = SteerRanker.rank(
			SteerConfig(freshness = 0.0, redditOrderTrust = 0.0), listOf(fresh, old), now
		)
		val freshFirst = SteerRanker.rank(
			SteerConfig(freshness = 1.0, redditOrderTrust = 0.0), listOf(old, fresh), now
		)
		assertEquals("old", popular.first().post.id)
		assertEquals("fresh", freshFirst.first().post.id)
	}

	@Test
	fun learningMovesWeightAndIsLogged() {
		val cfg = SteerLearner.apply(SteerConfig(), "r/Pics", Signal.UPVOTED, now)
		val rule = cfg.rule("pics")
		assertTrue(rule.learned > 0)
		assertEquals(1, rule.interactions)
		assertEquals(1, cfg.log.size)
		assertEquals("pics", cfg.log.single().subreddit)
	}

	@Test
	fun lockedSubredditsDoNotLearn() {
		val cfg = SteerConfig().withRule(SubredditRule("pics", locked = true))
		val after = SteerLearner.apply(cfg, "pics", Signal.UPVOTED, now)
		assertEquals(0.0, after.rule("pics").learned, 0.0)
		assertTrue(after.log.isEmpty())
	}

	@Test
	fun learningIsClamped() {
		var cfg = SteerConfig(learningRate = 2.0)
		repeat(500) { cfg = SteerLearner.apply(cfg, "pics", Signal.HIDDEN, now) }
		assertEquals(SteerConfig.LEARNED_MIN, cfg.rule("pics").learned, 1e-9)
		assertEquals(SteerConfig.MAX_LOG_ENTRIES, cfg.log.size)
	}

	@Test
	fun learningOffChangesNothing() {
		val cfg = SteerConfig(learningEnabled = false)
		assertEquals(cfg, SteerLearner.apply(cfg, "pics", Signal.UPVOTED, now))
	}

	@Test
	fun configSurvivesJsonRoundTrip() {
		val cfg = SteerLearner.apply(
			SteerConfig(keywords = listOf(KeywordRule("enb"))),
			"pics",
			Signal.SAVED,
			now
		)
		val json = Json { encodeDefaults = true }
		val text = json.encodeToString(SteerConfig.serializer(), cfg)
		assertEquals(cfg, json.decodeFromString(SteerConfig.serializer(), text))
	}

	@Test
	fun formatWeightRounds() {
		assertEquals("1.25", SteerRanker.formatWeight(1.2499999))
		assertEquals("0.5", SteerRanker.formatWeight(0.5))
	}
}
