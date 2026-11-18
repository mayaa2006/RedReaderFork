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

package org.quantumbadger.redreader.steer

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Steer: a user-controlled ranking layer on top of Reddit listings.
//
// Everything in this file is plain Kotlin with no Android dependencies, so it
// can be unit tested on the JVM. Persistence and wiring live in SteerManager.

@Serializable
enum class KeywordTarget { ANY, TITLE, FLAIR, DOMAIN }

@Serializable
data class KeywordRule(
	val term: String,
	// Multiplier applied when the keyword matches. Ignored if mute is true.
	val weight: Double = 1.5,
	val mute: Boolean = false,
	val target: KeywordTarget = KeywordTarget.ANY
)

@Serializable
data class SubredditRule(
	// Lowercase subreddit name without the r/ prefix
	val name: String,
	// The weight the user set by hand. 0 hides the subreddit entirely.
	val manualWeight: Double = 1.0,
	// What the app has learned, stored as a log-multiplier (0 = no change).
	val learned: Double = 0.0,
	// When locked, learning never changes this subreddit.
	val locked: Boolean = false,
	val interactions: Int = 0
) {
	val learnedMultiplier: Double get() = exp(learned)
	val effectiveWeight: Double get() = manualWeight * learnedMultiplier
}

@Serializable
enum class Signal(val delta: Double, val label: String) {
	OPENED(0.03, "Opened"),
	OPENED_COMMENTS(0.05, "Opened comments"),
	UPVOTED(0.08, "Upvoted"),
	SAVED(0.08, "Saved"),
	DOWNVOTED(-0.10, "Downvoted"),
	HIDDEN(-0.15, "Hidden")
}

@Serializable
data class LearnLogEntry(
	val timeMs: Long,
	val subreddit: String,
	val signal: Signal,
	val before: Double,
	val after: Double
)

@Serializable
data class SteerConfig(
	val enabled: Boolean = true,
	val rankFrontPage: Boolean = true,
	val rankOtherListings: Boolean = false,
	// 0 = favour popular posts, 1 = favour fresh posts
	val freshness: Double = 0.4,
	// How much Reddit's own ordering still counts (0..1)
	val redditOrderTrust: Double = 0.3,
	val learningEnabled: Boolean = true,
	// Multiplies every learning step (0.25 = slow, 2 = fast)
	val learningRate: Double = 1.0,
	val subreddits: Map<String, SubredditRule> = emptyMap(),
	val keywords: List<KeywordRule> = emptyList(),
	val log: List<LearnLogEntry> = emptyList()
) {
	fun rule(subreddit: String): SubredditRule {
		val key = normaliseSubreddit(subreddit)
		return subreddits[key] ?: SubredditRule(key)
	}

	fun withRule(rule: SubredditRule): SteerConfig =
		copy(subreddits = subreddits + (rule.name to rule))

	companion object {
		const val LEARNED_MIN = -1.5
		const val LEARNED_MAX = 1.5
		const val MAX_LOG_ENTRIES = 100

		fun normaliseSubreddit(name: String): String =
			name.trim().removePrefix("/").removePrefix("r/").lowercase()
	}
}

// The minimum a ranker needs to know about a post
data class PostFeatures(
	val id: String,
	val subreddit: String,
	val title: String,
	val flair: String?,
	val domain: String?,
	val score: Int,
	val comments: Int,
	val createdUtcMs: Long
)

data class Factor(val label: String, val detail: String)

data class ScoredPost(
	val post: PostFeatures,
	val score: Double,
	val hidden: Boolean,
	val factors: List<Factor>
)

object SteerRanker {

	private fun clamp01(v: Double) = max(0.0, min(1.0, v))

	fun popularity(score: Int, comments: Int): Double {
		val s = clamp01(log10(1.0 + max(score, 0)) / 4.5)
		val c = clamp01(log10(1.0 + max(comments, 0)) / 3.5)
		return 0.8 * s + 0.2 * c
	}

	// Half-life of roughly 7 hours
	fun freshness(ageHours: Double): Double = exp(-max(ageHours, 0.0) / 10.0)

	fun keywordMatches(rule: KeywordRule, post: PostFeatures): Boolean {
		val term = rule.term.trim().lowercase()
		if (term.isEmpty()) {
			return false
		}
		val title = post.title.lowercase()
		val flair = post.flair?.lowercase() ?: ""
		val domain = post.domain?.lowercase() ?: ""
		return when (rule.target) {
			KeywordTarget.TITLE -> title.contains(term)
			KeywordTarget.FLAIR -> flair.contains(term)
			KeywordTarget.DOMAIN -> domain.contains(term)
			KeywordTarget.ANY ->
				title.contains(term) || flair.contains(term) || domain.contains(term)
		}
	}

	// Avoids String.format, which lint flags for implicit locale use
	fun formatWeight(weight: Double): String =
		((weight * 100).roundToInt() / 100.0).toString()

	private fun pct(multiplier: Double): String {
		val p = ((multiplier - 1.0) * 100).roundToInt()
		return if (p >= 0) "+$p%" else "$p%"
	}

	/**
	 * Scores a batch of posts. The input order is taken to be Reddit's own
	 * ranking. Returns all posts (hidden ones flagged), sorted best first.
	 */
	fun rank(
		config: SteerConfig,
		posts: List<PostFeatures>,
		nowMs: Long,
		reorder: Boolean = true
	): List<ScoredPost> {

		val n = posts.size
		val scored = posts.mapIndexed { index, post ->
			val factors = ArrayList<Factor>()
			var hidden = false

			val rule = config.rule(post.subreddit)
			val ageHours = (nowMs - post.createdUtcMs) / 3_600_000.0
			val pop = popularity(post.score, post.comments)
			val fresh = freshness(ageHours)
			val redditPos = if (n <= 1) 1.0 else 1.0 - index.toDouble() / (n - 1)

			val f = clamp01(config.freshness)
			val t = clamp01(config.redditOrderTrust)
			val base = t * redditPos + (1 - t) * ((1 - f) * pop + f * fresh)

			factors.add(Factor(
				"Base score",
				"popularity ${(pop * 100).roundToInt()}, " +
						"freshness ${(fresh * 100).roundToInt()}, " +
						"Reddit position ${index + 1}/$n"
			))

			var mult = 1.0

			if (rule.manualWeight <= 0.0) {
				hidden = true
				factors.add(Factor("r/${rule.name}", "hidden (your weight is 0)"))
			} else {
				if (rule.manualWeight != 1.0) {
					factors.add(Factor(
						"r/${rule.name}",
						"your weight ×${formatWeight(rule.manualWeight)}"
					))
				}
				if (rule.learned != 0.0) {
					factors.add(Factor(
						"r/${rule.name}",
						"learned ${pct(rule.learnedMultiplier)}" +
								if (rule.locked) " (locked)" else ""
					))
				}
				mult *= rule.effectiveWeight
			}

			for (kw in config.keywords) {
				if (!keywordMatches(kw, post)) {
					continue
				}
				if (kw.mute) {
					hidden = true
					factors.add(Factor("Keyword \"${kw.term}\"", "muted"))
				} else {
					mult *= max(kw.weight, 0.0)
					factors.add(Factor("Keyword \"${kw.term}\"", pct(kw.weight)))
				}
			}

			ScoredPost(post, base * mult, hidden, factors)
		}

		return if (reorder) {
			// Stable sort: ties keep Reddit's order
			scored.sortedByDescending { it.score }
		} else {
			scored
		}
	}
}

object SteerLearner {

	fun apply(
		config: SteerConfig,
		subreddit: String,
		signal: Signal,
		nowMs: Long
	): SteerConfig {

		if (!config.enabled || !config.learningEnabled) {
			return config
		}

		val rule = config.rule(subreddit)
		if (rule.locked || rule.name.isEmpty()) {
			return config
		}

		val step = signal.delta * max(config.learningRate, 0.0)
		val after = max(
			SteerConfig.LEARNED_MIN,
			min(SteerConfig.LEARNED_MAX, rule.learned + step)
		)

		val entry = LearnLogEntry(nowMs, rule.name, signal, rule.learned, after)
		val newLog = (config.log + entry).takeLast(SteerConfig.MAX_LOG_ENTRIES)

		return config
			.withRule(rule.copy(learned = after, interactions = rule.interactions + 1))
			.copy(log = newLog)
	}

	fun describe(learned: Double): String {
		val p = ((exp(learned) - 1.0) * 100).roundToInt()
		return when {
			p == 0 -> "no change"
			p > 0 -> "shown more (+$p%)"
			else -> "shown less ($p%)"
		}
	}

	// Converts a percentage the user types back into a log-multiplier
	fun learnedFromPercent(percent: Double): Double =
		max(
			SteerConfig.LEARNED_MIN,
			min(SteerConfig.LEARNED_MAX, ln(max(1.0 + percent / 100.0, 0.01)))
		)
}
