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

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import org.quantumbadger.redreader.reddit.kthings.RedditPost
import org.quantumbadger.redreader.reddit.url.MultiredditPostListURL
import org.quantumbadger.redreader.reddit.url.PostListingURL
import org.quantumbadger.redreader.reddit.url.RedditURLParser
import org.quantumbadger.redreader.reddit.url.SubredditPostListURL
import java.util.Collections

/**
 * Android-side glue for Steer: stores the config, applies it to downloaded
 * post batches, and feeds interactions to the learner.
 */
object SteerManager {

	private const val TAG = "SteerManager"
	private const val PREFS_NAME = "steer"
	private const val PREFS_KEY_CONFIG = "config_v1"

	// Posts fetched per page when Steer is ranking, so there is more to choose from
	private const val RANKED_PAGE_SIZE = 100

	private const val MAX_EXPLANATIONS = 3000

	private val json = Json {
		ignoreUnknownKeys = true
		encodeDefaults = true
	}

	private val lock = Any()
	private var prefs: SharedPreferences? = null
	private val state = MutableStateFlow(SteerConfig())

	// Post ID -> why it was ranked where it was. Kept in memory only.
	private val explanations: MutableMap<String, ScoredPost> = Collections.synchronizedMap(
		object : LinkedHashMap<String, ScoredPost>(256, 0.75f, true) {
			override fun removeEldestEntry(
				eldest: MutableMap.MutableEntry<String, ScoredPost>?
			) = size > MAX_EXPLANATIONS
		}
	)

	class BatchResult(
		@JvmField val posts: List<RedditPost>,
		@JvmField val hiddenCount: Int
	)

	private fun ensureLoaded(context: Context) {
		synchronized(lock) {
			if (prefs != null) {
				return
			}
			val p = context.applicationContext
				.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
			prefs = p
			val raw = p.getString(PREFS_KEY_CONFIG, null)
			if (raw != null) {
				try {
					state.value = json.decodeFromString(SteerConfig.serializer(), raw)
				} catch (e: Exception) {
					Log.e(TAG, "Could not read Steer config, using defaults", e)
				}
			}
		}
	}

	@JvmStatic
	fun config(context: Context): SteerConfig {
		ensureLoaded(context)
		return state.value
	}

	fun configFlow(context: Context): StateFlow<SteerConfig> {
		ensureLoaded(context)
		return state
	}

	fun update(context: Context, transform: (SteerConfig) -> SteerConfig) {
		ensureLoaded(context)
		synchronized(lock) {
			val updated = transform(state.value)
			state.value = updated
			prefs?.edit()
				?.putString(PREFS_KEY_CONFIG, json.encodeToString(SteerConfig.serializer(), updated))
				?.apply()
		}
	}

	fun exportJson(context: Context): String =
		json.encodeToString(SteerConfig.serializer(), config(context))

	fun importJson(context: Context, raw: String): Boolean {
		return try {
			val imported = json.decodeFromString(SteerConfig.serializer(), raw)
			update(context) { imported }
			true
		} catch (e: Exception) {
			Log.e(TAG, "Import failed", e)
			false
		}
	}

	private fun isFrontPage(url: PostListingURL): Boolean =
		url.pathType() == RedditURLParser.SUBREDDIT_POST_LISTING_URL
				&& url.asSubredditPostListURL().type == SubredditPostListURL.Type.FRONTPAGE

	@JvmStatic
	fun shouldRank(context: Context, url: PostListingURL?): Boolean {
		if (url == null) {
			return false
		}
		val cfg = config(context)
		if (!cfg.enabled) {
			return false
		}
		return when (url.pathType()) {
			RedditURLParser.SUBREDDIT_POST_LISTING_URL ->
				if (isFrontPage(url)) cfg.rankFrontPage else cfg.rankOtherListings
			RedditURLParser.MULTIREDDIT_POST_LISTING_URL -> cfg.rankOtherListings
			else -> false
		}
	}

	@JvmStatic
	fun shouldApplyMutes(context: Context, url: PostListingURL?): Boolean {
		if (url == null || !config(context).enabled) {
			return false
		}
		// Never filter a user's own profile listing
		return url.pathType() != RedditURLParser.USER_POST_LISTING_URL
	}

	/**
	 * When ranking, fetch bigger pages so there is more for Steer to reorder.
	 */
	@JvmStatic
	fun adjustListingUrl(context: Context, url: PostListingURL?): PostListingURL? {
		if (url == null || !shouldRank(context, url)) {
			return url
		}
		return when (url) {
			is SubredditPostListURL ->
				if (url.limit == null) url.limit(RANKED_PAGE_SIZE) else url
			is MultiredditPostListURL ->
				if (url.limit == null) url.limit(RANKED_PAGE_SIZE) else url
			else -> url
		}
	}

	private fun features(post: RedditPost) = PostFeatures(
		id = post.getIdAlone(),
		subreddit = post.subreddit.decoded,
		title = post.title?.decoded ?: "",
		flair = post.link_flair_text?.decoded,
		domain = post.domain?.decoded,
		score = post.score,
		comments = post.num_comments,
		createdUtcMs = post.created_utc.value.toUtcMs()
	)

	/**
	 * Applies mutes and (optionally) Steer's ranking to one downloaded page.
	 * Pinned/stickied posts keep their place at the top.
	 */
	@JvmStatic
	fun processBatch(
		context: Context,
		posts: List<RedditPost>,
		rank: Boolean,
		applyMutes: Boolean
	): BatchResult {

		if (!rank && !applyMutes) {
			return BatchResult(posts, 0)
		}

		val cfg = config(context)
		val byId = posts.associateBy { it.getIdAlone() }

		val stickied = posts.filter { it.stickied }
		val rest = posts.filter { !it.stickied }

		val scored = SteerRanker.rank(
			cfg,
			rest.map { features(it) },
			System.currentTimeMillis(),
			reorder = rank
		)

		var hidden = 0
		val result = ArrayList<RedditPost>(posts.size)
		result.addAll(stickied)

		for (sp in scored) {
			explanations[sp.post.id] = sp
			if (sp.hidden && applyMutes) {
				hidden++
				continue
			}
			byId[sp.post.id]?.let { result.add(it) }
		}

		return BatchResult(result, hidden)
	}

	@JvmStatic
	fun explanationFor(postId: String): ScoredPost? = explanations[postId]

	@JvmStatic
	fun recordSignal(context: Context, post: RedditPost, signal: Signal) {
		try {
			update(context) {
				SteerLearner.apply(it, post.subreddit.decoded, signal, System.currentTimeMillis())
			}
		} catch (e: Exception) {
			// Learning must never break normal app behaviour
			Log.e(TAG, "Failed to record signal", e)
		}
	}
}
