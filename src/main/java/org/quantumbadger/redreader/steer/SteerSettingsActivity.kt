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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.compose.activity.ComposeBaseActivity
import kotlin.math.exp
import kotlin.math.roundToInt

class SteerSettingsActivity : ComposeBaseActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setTitle(R.string.steer_settings_title)
		setContentCompose {
			SteerSettingsScreen(onBack = { finish() })
		}
	}
}

private fun pctFromLearned(learned: Double): Int = ((exp(learned) - 1.0) * 100).roundToInt()

@Composable
private fun SteerSettingsScreen(onBack: () -> Unit) {

	val context = LocalContext.current
	val cfg by SteerManager.configFlow(context).collectAsState()

	fun edit(transform: (SteerConfig) -> SteerConfig) = SteerManager.update(context, transform)

	LazyColumn(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background)
			.windowInsetsPadding(WindowInsets.safeDrawing),
		verticalArrangement = Arrangement.spacedBy(12.dp)
	) {

		item {
			Row(
				modifier = Modifier.fillMaxWidth().padding(8.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				TextButton(onClick = onBack) { Text("← Back") }
				Text(
					"Steer algorithm",
					style = MaterialTheme.typography.titleLarge,
					color = MaterialTheme.colorScheme.onBackground
				)
			}
		}

		item {
			Section("Ranking") {
				SwitchRow("Steer is on", cfg.enabled) { v -> edit { it.copy(enabled = v) } }
				SwitchRow("Rank my front page", cfg.rankFrontPage) { v ->
					edit { it.copy(rankFrontPage = v) }
				}
				SwitchRow(
					"Also rank subreddits & multireddits",
					cfg.rankOtherListings
				) { v -> edit { it.copy(rankOtherListings = v) } }
				Hint(
					"When ranking, Steer loads 100 posts at a time and reorders them. " +
							"Keyword mutes apply everywhere except user profiles."
				)
			}
		}

		item {
			Section("Feed mix") {
				CommitSlider(
					label = "Popular ↔ Fresh",
					valueText = { v -> "${(v * 100).roundToInt()}% fresh" },
					value = cfg.freshness.toFloat(),
					range = 0f..1f
				) { v -> edit { it.copy(freshness = v.toDouble()) } }
				CommitSlider(
					label = "How much Reddit's own order counts",
					valueText = { v -> "${(v * 100).roundToInt()}%" },
					value = cfg.redditOrderTrust.toFloat(),
					range = 0f..1f
				) { v -> edit { it.copy(redditOrderTrust = v.toDouble()) } }
			}
		}

		item {
			Section("Learning") {
				SwitchRow("Learn from what I open, vote, save and hide", cfg.learningEnabled) {
					v -> edit { it.copy(learningEnabled = v) }
				}
				CommitSlider(
					label = "Learning speed",
					valueText = { v -> "×" + SteerRanker.formatWeight(v.toDouble()) },
					value = cfg.learningRate.toFloat(),
					range = 0.25f..2f
				) { v -> edit { it.copy(learningRate = v.toDouble()) } }
				Hint(
					"Everything Steer learns shows up under Subreddits below, where you " +
							"can change it, lock it, or reset it."
				)
				OutlinedButton(onClick = {
					edit { c ->
						c.copy(subreddits = c.subreddits.mapValues {
							if (it.value.locked) it.value else it.value.copy(learned = 0.0)
						})
					}
				}) { Text("Reset all learned changes (keeps locked ones)") }
			}
		}

		item {
			KeywordSection(cfg) { edit(it) }
		}

		item {
			Section("Subreddits") {
				var newSub by remember { mutableStateOf("") }
				Row(verticalAlignment = Alignment.CenterVertically) {
					OutlinedTextField(
						value = newSub,
						onValueChange = { newSub = it },
						label = { Text("Add subreddit") },
						singleLine = true,
						modifier = Modifier.weight(1f)
					)
					TextButton(onClick = {
						val name = SteerConfig.normaliseSubreddit(newSub)
						if (name.isNotEmpty()) {
							edit { c -> c.withRule(c.rule(name)) }
						}
						newSub = ""
					}) { Text("Add") }
				}
				Hint("Weight 0 hides a subreddit. Subreddits appear here once you add them " +
						"or Steer learns something about them.")
			}
		}

		val subs = cfg.subreddits.values.sortedWith(
			compareByDescending<SubredditRule> { it.interactions }.thenBy { it.name }
		)
		items(subs, key = { "sub_" + it.name }) { rule ->
			SubredditCard(rule, edit = { edit(it) })
		}

		item {
			Section("What Steer learned recently") {
				if (cfg.log.isEmpty()) {
					Hint("Nothing yet.")
				}
				for (entry in cfg.log.takeLast(30).reversed()) {
					val ago = DateUtils.getRelativeTimeSpanString(
						entry.timeMs,
						System.currentTimeMillis(),
						DateUtils.MINUTE_IN_MILLIS
					)
					Text(
						"r/${entry.subreddit} · ${entry.signal.label} · " +
								"${signed(pctFromLearned(entry.before))} → " +
								"${signed(pctFromLearned(entry.after))} · $ago",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurface
					)
				}
			}
		}

		item {
			Section("Backup") {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					OutlinedButton(onClick = {
						copyToClipboard(context, SteerManager.exportJson(context))
						Toast.makeText(context, "Copied settings", Toast.LENGTH_SHORT).show()
					}) { Text("Copy settings") }
					OutlinedButton(onClick = {
						val raw = readClipboard(context)
						val ok = raw != null && SteerManager.importJson(context, raw)
						Toast.makeText(
							context,
							if (ok) "Settings imported" else "Clipboard doesn't hold Steer settings",
							Toast.LENGTH_SHORT
						).show()
					}) { Text("Paste settings") }
				}
			}
		}

		item { Spacer(Modifier.height(24.dp)) }
	}
}

private fun signed(p: Int) = if (p >= 0) "+$p%" else "$p%"

@Composable
private fun KeywordSection(cfg: SteerConfig, edit: ((SteerConfig) -> SteerConfig) -> Unit) {
	Section("Keywords") {
		var term by remember { mutableStateOf("") }
		OutlinedTextField(
			value = term,
			onValueChange = { term = it },
			label = { Text("Word or phrase") },
			singleLine = true,
			modifier = Modifier.fillMaxWidth()
		)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			OutlinedButton(onClick = {
				if (term.isNotBlank()) {
					edit { it.copy(keywords = it.keywords + KeywordRule(term.trim(), 1.5)) }
				}
				term = ""
			}) { Text("Boost") }
			OutlinedButton(onClick = {
				if (term.isNotBlank()) {
					edit { it.copy(keywords = it.keywords + KeywordRule(term.trim(), 0.5)) }
				}
				term = ""
			}) { Text("Bury") }
			OutlinedButton(onClick = {
				if (term.isNotBlank()) {
					edit {
						it.copy(keywords = it.keywords + KeywordRule(term.trim(), 1.0, mute = true))
					}
				}
				term = ""
			}) { Text("Mute") }
		}

		cfg.keywords.forEachIndexed { index, kw ->
			HorizontalDivider()
			fun replace(newKw: KeywordRule?) = edit { c ->
				val list = c.keywords.toMutableList()
				if (newKw == null) {
					list.removeAt(index)
				} else {
					list[index] = newKw
				}
				c.copy(keywords = list)
			}

			Row(verticalAlignment = Alignment.CenterVertically) {
				Text(
					"\"${kw.term}\"",
					fontWeight = FontWeight.Bold,
					color = MaterialTheme.colorScheme.onSurface,
					modifier = Modifier.weight(1f)
				)
				TextButton(onClick = {
					val next = KeywordTarget.entries[
						(kw.target.ordinal + 1) % KeywordTarget.entries.size]
					replace(kw.copy(target = next))
				}) { Text("in: " + kw.target.name.lowercase()) }
				TextButton(onClick = { replace(null) }) { Text("Remove") }
			}
			SwitchRow("Mute (hide matching posts)", kw.mute) { v -> replace(kw.copy(mute = v)) }
			if (!kw.mute) {
				CommitSlider(
					label = "Weight",
					valueText = { v -> "×" + SteerRanker.formatWeight(v.toDouble()) },
					value = kw.weight.toFloat(),
					range = 0.1f..3f
				) { v -> replace(kw.copy(weight = v.toDouble())) }
			}
		}
	}
}

@Composable
private fun SubredditCard(rule: SubredditRule, edit: ((SteerConfig) -> SteerConfig) -> Unit) {

	fun replace(transform: (SubredditRule) -> SubredditRule) =
		edit { c -> c.withRule(transform(c.rule(rule.name))) }

	Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
		Column(modifier = Modifier.padding(12.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text(
					"r/${rule.name}",
					style = MaterialTheme.typography.titleMedium,
					modifier = Modifier.weight(1f)
				)
				Text(
					"${rule.interactions} interactions",
					style = MaterialTheme.typography.bodySmall
				)
			}
			CommitSlider(
				label = "Your weight",
				valueText = { v ->
					if (v <= 0.001f) "hidden" else "×" + SteerRanker.formatWeight(v.toDouble())
				},
				value = rule.manualWeight.toFloat(),
				range = 0f..3f
			) { v -> replace { it.copy(manualWeight = v.toDouble()) } }
			CommitSlider(
				label = "Learned by Steer",
				valueText = { v -> SteerLearner.describe(v.toDouble()) },
				value = rule.learned.toFloat(),
				range = SteerConfig.LEARNED_MIN.toFloat()..SteerConfig.LEARNED_MAX.toFloat()
			) { v -> replace { it.copy(learned = v.toDouble()) } }
			Row(verticalAlignment = Alignment.CenterVertically) {
				Switch(
					checked = rule.locked,
					onCheckedChange = { v -> replace { it.copy(locked = v) } }
				)
				Spacer(Modifier.width(8.dp))
				Text("Lock (stop learning)", modifier = Modifier.weight(1f))
				TextButton(onClick = { replace { it.copy(learned = 0.0) } }) {
					Text("Reset learned")
				}
				TextButton(onClick = {
					edit { c -> c.copy(subreddits = c.subreddits - rule.name) }
				}) { Text("Forget") }
			}
		}
	}
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
	Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
		Column(
			modifier = Modifier.padding(12.dp),
			verticalArrangement = Arrangement.spacedBy(6.dp)
		) {
			Text(title, style = MaterialTheme.typography.titleMedium)
			content()
		}
	}
}

@Composable
private fun Hint(text: String) {
	Text(text, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		Text(label, modifier = Modifier.weight(1f))
		Switch(checked = checked, onCheckedChange = onChange)
	}
}

/**
 * A slider that only saves when the user lets go, so dragging doesn't write
 * the config on every frame.
 */
@Composable
private fun CommitSlider(
	label: String,
	valueText: (Float) -> String,
	value: Float,
	range: ClosedFloatingPointRange<Float>,
	onCommit: (Float) -> Unit
) {
	var local by remember(value) { mutableFloatStateOf(value) }
	Column {
		Row {
			Text(label, modifier = Modifier.weight(1f))
			Text(valueText(local), fontWeight = FontWeight.Bold)
		}
		Slider(
			value = local.coerceIn(range.start, range.endInclusive),
			onValueChange = { local = it },
			onValueChangeFinished = { onCommit(local) },
			valueRange = range
		)
	}
}

private fun copyToClipboard(context: Context, text: String) {
	val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
	cm.setPrimaryClip(ClipData.newPlainText("Steer settings", text))
}

private fun readClipboard(context: Context): String? {
	val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
	val clip = cm.primaryClip ?: return null
	if (clip.itemCount == 0) {
		return null
	}
	return clip.getItemAt(0).coerceToText(context)?.toString()
}
