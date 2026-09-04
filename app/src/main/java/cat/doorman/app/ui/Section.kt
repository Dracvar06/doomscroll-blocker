package cat.doorman.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cat.doorman.app.R

/**
 * A part of the settings screen that can be folded away.
 *
 * The screen had become one long scroll in which a blocked app, the language
 * picker and a diagnostics tool all carried exactly the same weight, so the
 * thing people open Doorman for -- what it blocks -- had to be found among the
 * rest of it.
 *
 * Three kinds of thing live on that screen: what Doorman blocks, how Doorman
 * behaves, and the app itself. Only the first is why anybody came, so it stays
 * open and the other two fold shut. Nothing is hidden: a fold is one tap, it
 * says what is inside it, and it remembers. What changes is that the default
 * view is now the question people came to answer.
 *
 * Grouping rather than colour, deliberately. Tinting the sections would have
 * separated them too, but colour in Doorman already means something -- held,
 * open, wrong -- and a hue that means "this is the language section" would
 * spend that meaning on nothing.
 */
@Composable
fun Section(
    title: String,
    open: Boolean,
    onOpenChanged: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    HorizontalDivider()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenChanged(!open) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        Text(
            text = stringResource(if (open) R.string.card_close else R.string.section_show),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (open) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

/**
 * Keys for remembering which sections are folded.
 *
 * They share the store that remembers which app cards are open. Both answer the
 * same question -- "is this part of the screen showing?" -- and giving the
 * second one its own preference file would be two mechanisms for one idea.
 * Prefixed so they can never collide with a package name.
 */
const val SECTION_BEHAVIOUR = "section:behaviour"
const val SECTION_ABOUT = "section:about"
