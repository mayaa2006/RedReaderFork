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

import android.content.Intent
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.activities.BaseActivity
import org.quantumbadger.redreader.reddit.prepared.RedditPreparedPost
import kotlin.math.max
import kotlin.math.min

/**
 * Explains why a post appeared where it did, and lets the user nudge the
 * subreddit's weight directly from the post.
 */
object SteerWhyDialog {

	private const val NUDGE_UP = 1.25
	private const val NUDGE_DOWN = 0.8
	private const val WEIGHT_MAX = 5.0

	fun show(activity: BaseActivity, post: RedditPreparedPost) {

		val src = post.src.src
		val subreddit = SteerConfig.normaliseSubreddit(src.subreddit.decoded)
		val explanation = SteerManager.explanationFor(src.getIdAlone())
		val rule = SteerManager.config(activity).rule(subreddit)

		val text = StringBuilder()

		if (explanation == null) {
			text.append("Steer didn't rank this post (it came from a listing ")
				.append("Steer leaves in Reddit's order).\n\n")
		} else {
			for (factor in explanation.factors) {
				text.append("• ").append(factor.label).append(": ")
					.append(factor.detail).append('\n')
			}
			text.append("\nFinal score: ")
				.append(SteerRanker.formatWeight(explanation.score))
				.append("\n\n")
		}

		text.append("r/").append(subreddit).append(": your weight ×")
			.append(SteerRanker.formatWeight(rule.manualWeight))
			.append(", learned ")
			.append(SteerLearner.describe(rule.learned))
		if (rule.locked) {
			text.append(" (locked)")
		}

		fun nudge(factor: Double) {
			SteerManager.update(activity) { cfg ->
				val r = cfg.rule(subreddit)
				val newWeight = min(WEIGHT_MAX, max(0.05, r.manualWeight * factor))
				cfg.withRule(r.copy(manualWeight = newWeight))
			}
			Toast.makeText(
				activity,
				"r/$subreddit weight is now ×" + SteerRanker.formatWeight(
					SteerManager.config(activity).rule(subreddit).manualWeight
				),
				Toast.LENGTH_SHORT
			).show()
		}

		MaterialAlertDialogBuilder(activity)
			.setTitle(R.string.steer_action_why)
			.setMessage(text.toString())
			.setPositiveButton(R.string.steer_show_more) { _, _ -> nudge(NUDGE_UP) }
			.setNegativeButton(R.string.steer_show_less) { _, _ -> nudge(NUDGE_DOWN) }
			.setNeutralButton(R.string.steer_open_settings) { _, _ ->
				activity.startActivity(Intent(activity, SteerSettingsActivity::class.java))
			}
			.show()
	}
}
